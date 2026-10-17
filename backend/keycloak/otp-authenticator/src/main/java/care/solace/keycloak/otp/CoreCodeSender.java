package care.solace.keycloak.otp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Hands a code to Core, which words it from notification-copy.md and sends the SMS or email
 * (CLAUDE.md, Sign-in: "Core sends the SMS"). Calls {@code POST /internal/v1/sign-in-codes}
 * (contracts/openapi/core-internal.yaml) with a client-credentials token of the staff realm's
 * {@code keycloak-code-sender} client, which alone has the scope {@code core:sign-in-codes.send}.
 *
 * <p>Settings come from the environment:
 *
 * <ul>
 *   <li>{@code SOLACE_CORE_URL}: Core inside the cluster (default {@code http://localhost:8101})
 *   <li>{@code SOLACE_CODE_SENDER_TOKEN_URL}: the staff realm's token endpoint as Keycloak reaches
 *       itself (default {@code
 *       http://localhost:8080/realms/solace-staff/protocol/openid-connect/token})
 *   <li>{@code SOLACE_CODE_SENDER_SECRET}: the client's secret
 * </ul>
 *
 * A failure is logged without the code or the contact; the person sees the same "code sent" answer,
 * so a delivery problem never reveals whether the contact has an account.
 */
public final class CoreCodeSender implements CodeSender {

  public static final String ID = "core";
  static final String CLIENT_ID = "keycloak-code-sender";
  private static final Logger LOG = Logger.getLogger(CoreCodeSender.class);
  // Placeholders, not in the specs: a sign-in should not hang on a slow Core.
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static volatile CoreCodeSender instance;

  private final URI codesUrl;
  private final URI tokenUrl;
  private final String secret;
  private final HttpClient http;
  private final Clock clock;
  private volatile String token;
  private volatile Instant tokenExpires = Instant.EPOCH;

  CoreCodeSender(String coreUrl, String tokenUrl, String secret, HttpClient http, Clock clock) {
    this.codesUrl = URI.create(stripSlash(coreUrl) + "/internal/v1/sign-in-codes");
    this.tokenUrl = URI.create(tokenUrl);
    this.secret = secret;
    this.http = http;
    this.clock = clock;
  }

  /** One sender per Keycloak node, so its token is reused until it expires. */
  static CoreCodeSender instance() {
    if (instance == null) {
      synchronized (CoreCodeSender.class) {
        if (instance == null) {
          instance = fromEnvironment(System::getenv);
        }
      }
    }
    return instance;
  }

  static CoreCodeSender fromEnvironment(Function<String, String> env) {
    String secret = env.apply("SOLACE_CODE_SENDER_SECRET");
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("SOLACE_CODE_SENDER_SECRET is not set");
    }
    return new CoreCodeSender(
        orDefault(env.apply("SOLACE_CORE_URL"), "http://localhost:8101"),
        orDefault(
            env.apply("SOLACE_CODE_SENDER_TOKEN_URL"),
            "http://localhost:8080/realms/solace-staff/protocol/openid-connect/token"),
        secret,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        Clock.systemUTC());
  }

  @Override
  public void send(Contact contact, String code, String locale, Duration lifetime) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("channel", contact.kind() == Contact.Kind.PHONE ? "SMS" : "EMAIL");
    body.put("to", contact.value());
    body.put("code", code);
    body.put("expiresInMinutes", Math.max(1, lifetime.toMinutes()));
    if ("en".equals(locale) || "fr".equals(locale)) {
      body.put("locale", locale);
    }
    try {
      HttpResponse<Void> response = post(body, false);
      if (response.statusCode() == 401) {
        response = post(body, true);
      }
      if (response.statusCode() != 202) {
        LOG.warnf("Core did not accept a sign-in code for sending: HTTP %d", response.statusCode());
      }
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Could not hand a sign-in code to Core: %s", e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private HttpResponse<Void> post(Map<String, Object> body, boolean freshToken)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(codesUrl)
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token(freshToken))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonSerialization.writeValueAsString(body)))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private synchronized String token(boolean fresh) throws IOException, InterruptedException {
    if (!fresh && token != null && clock.instant().isBefore(tokenExpires)) {
      return token;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + CLIENT_ID
            + "&client_secret="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(tokenUrl)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("token endpoint answered HTTP " + response.statusCode());
    }
    Map<?, ?> tokens = JsonSerialization.readValue(response.body(), Map.class);
    token = String.valueOf(tokens.get("access_token"));
    long lifetime = ((Number) tokens.get("expires_in")).longValue();
    // Renew a little before Keycloak would refuse it.
    tokenExpires = clock.instant().plusSeconds(Math.max(0, lifetime - 30));
    return token;
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
