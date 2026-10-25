package care.solace.core.payments;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Notch Pay (D35), from its public documentation (developer.notchpay.co): a payment is initialised,
 * then charged to the wallet with a channel ({@code cm.mtn}, {@code cm.orange}); a transfer goes to
 * a recipient created first. The public key goes in Authorization, the private key in X-Grant.
 *
 * <p>Check every call against Notch Pay's test mode before the pilot: the field names here follow
 * the documentation, which is not consistent everywhere (the charged phone is sent both as {@code
 * phone} and {@code account_number}).
 */
class NotchPayProvider implements PaymentProvider {

  private final String code;
  private final ProviderSecrets.Keys keys;
  private final RestClient http;

  NotchPayProvider(
      String code, URI baseUrl, ProviderSecrets.Keys keys, RestClient.Builder builder) {
    this.code = code;
    this.keys = keys;
    this.http =
        builder
            .clone()
            .baseUrl(baseUrl.toString())
            .defaultHeader("Authorization", keys.publicKey())
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  @Override
  public String name() {
    return code;
  }

  @Override
  public Started charge(ChargeRequest request) {
    try {
      Map<String, Object> init = new LinkedHashMap<>();
      init.put("amount", request.amount());
      init.put("currency", "XAF");
      init.put("reference", request.reference().toString());
      init.put("description", request.description());
      init.put("customer", Map.of("phone", request.phone()));
      JsonNode created;
      try {
        created = post("/payments", init, false);
      } catch (RestClientException e) {
        // Only initialised, never charged: the wallet holder was not asked.
        throw new Unavailable("notchpay payment init", e, true);
      }
      String ref = created.path("transaction").path("reference").asText();
      Map<String, Object> data =
          Map.of("phone", request.phone(), "account_number", request.phone());
      post("/payments/" + ref, Map.of("channel", channel(request.method()), "data", data), false);
      return new Started(ref, Status.PENDING);
    } catch (RestClientException e) {
      throw new Unavailable("notchpay charge", e);
    }
  }

  @Override
  public Outcome chargeStatus(String providerRef) {
    try {
      JsonNode t = get("/payments/" + providerRef, false).path("transaction");
      return new Outcome(NotchPayFormat.status(t.path("status").asText()), reason(t));
    } catch (RestClientException e) {
      throw new Unavailable("notchpay payment status", e);
    }
  }

  @Override
  public Started transfer(TransferRequest request) {
    try {
      Map<String, Object> recipient = new LinkedHashMap<>();
      recipient.put("channel", channel(request.method()));
      recipient.put("number", request.phone());
      recipient.put("phone", request.phone());
      recipient.put("country", "CM");
      recipient.put("name", request.name());
      recipient.put("description", request.description());
      String recipientId =
          post("/recipients", recipient, true).path("recipient").path("id").asText();
      Map<String, Object> transfer = new LinkedHashMap<>();
      transfer.put("amount", request.amount());
      transfer.put("currency", "XAF");
      transfer.put("recipient", recipientId);
      transfer.put("description", request.description());
      transfer.put("reference", request.reference().toString());
      JsonNode t = post("/transfers", transfer, true).path("transfer");
      return new Started(
          t.path("reference").asText(), NotchPayFormat.status(t.path("status").asText()));
    } catch (RestClientException e) {
      throw new Unavailable("notchpay transfer", e);
    }
  }

  @Override
  public Outcome transferStatus(String providerRef) {
    try {
      JsonNode t = get("/transfers/" + providerRef, true).path("transfer");
      return new Outcome(NotchPayFormat.status(t.path("status").asText()), reason(t));
    } catch (RestClientException e) {
      throw new Unavailable("notchpay transfer status", e);
    }
  }

  @Override
  public boolean verify(HttpHeaders headers, byte[] body) {
    return NotchPayFormat.verify(
        keys.webhookSecret(), headers.getFirst(NotchPayFormat.SIGNATURE_HEADER), body);
  }

  @Override
  public Optional<Notification> parse(byte[] body) {
    return NotchPayFormat.parse(body);
  }

  /** Lists one payment: moves nothing, and fails on a wrong key or address. */
  @Override
  public void test() {
    try {
      get("/payments?limit=1", false);
    } catch (RestClientException e) {
      throw new Unavailable("notchpay test", e);
    }
  }

  static String channel(Method method) {
    return method == Method.MTN_MOMO ? "cm.mtn" : "cm.orange";
  }

  private JsonNode post(String path, Object body, boolean grant) {
    var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
    if (grant) {
      spec = spec.header("X-Grant", keys.privateKey());
    }
    return spec.retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path, boolean grant) {
    var spec = http.get().uri(path);
    if (grant) {
      spec = spec.header("X-Grant", keys.privateKey());
    }
    return spec.retrieve().body(JsonNode.class);
  }

  private static String reason(JsonNode t) {
    String r = t.path("failure_reason").asText(t.path("message").asText(""));
    return r.isBlank() ? null : r;
  }
}
