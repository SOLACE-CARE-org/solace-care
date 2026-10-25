package care.solace.core.notifications;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Any SMS gateway with an HTTP API (D61): one POST per message, as JSON or a form, with fields the
 * row names. In each field's value, {@code {to}}, {@code {text}} and {@code {from}} are filled in.
 * The API key goes in the header the row names, after its prefix ({@code Bearer }, or none). Any
 * 2xx answer counts as sent. Nothing about the message is logged.
 *
 * <p>Settings: {@code url} (https), {@code format} ({@code JSON} or {@code FORM}), {@code fields}
 * (name to template), {@code from} (the sender id), {@code authHeader}, {@code authPrefix}.
 */
class HttpSmsSender implements SmsSender {

  private final String code;
  private final Settings settings;
  private final RestClient http;

  record Settings(
      URI url,
      boolean json,
      Map<String, String> fields,
      String from,
      String authHeader,
      String authPrefix) {

    @SuppressWarnings("unchecked")
    static Settings of(Map<String, Object> raw) {
      Object url = raw.get("url");
      Object fields = raw.get("fields");
      Object format = raw.getOrDefault("format", "JSON");
      if (!(url instanceof String u)
          || !(fields instanceof Map<?, ?> f)
          || f.isEmpty()
          || !("JSON".equals(format) || "FORM".equals(format))) {
        throw new IllegalArgumentException("HTTP_SMS needs url, fields and format JSON or FORM");
      }
      URI uri = URI.create(u);
      if (uri.getHost() == null || !secure(uri)) {
        throw new IllegalArgumentException("HTTP_SMS url must be https");
      }
      Map<String, String> templates = new LinkedHashMap<>();
      ((Map<Object, Object>) f)
          .forEach((k, v) -> templates.put(String.valueOf(k), String.valueOf(v)));
      if (templates.values().stream().noneMatch(v -> v.contains("{to}"))
          || templates.values().stream().noneMatch(v -> v.contains("{text}"))) {
        throw new IllegalArgumentException("HTTP_SMS fields must carry {to} and {text}");
      }
      return new Settings(
          uri,
          "JSON".equals(format),
          templates,
          String.valueOf(raw.getOrDefault("from", "Solace")),
          String.valueOf(raw.getOrDefault("authHeader", "Authorization")),
          String.valueOf(raw.getOrDefault("authPrefix", "Bearer ")));
    }

    /** https, or plain http to this machine only (local gateways and tests). */
    private static boolean secure(URI uri) {
      return "https".equals(uri.getScheme())
          || ("http".equals(uri.getScheme())
              && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
    }
  }

  HttpSmsSender(String code, Settings settings, String apiKey, RestClient.Builder builder) {
    this.code = code;
    this.settings = settings;
    var b = builder.clone();
    if (apiKey != null && !apiKey.isBlank()) {
      b = b.defaultHeader(settings.authHeader(), settings.authPrefix() + apiKey);
    }
    this.http = b.build();
  }

  @Override
  public void send(String phone, String text) {
    try {
      if (settings.json()) {
        Map<String, String> body = new LinkedHashMap<>();
        settings.fields().forEach((k, v) -> body.put(k, fill(v, phone, text)));
        http.post()
            .uri(settings.url())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
      } else {
        var body = new LinkedMultiValueMap<String, String>();
        settings.fields().forEach((k, v) -> body.add(k, fill(v, phone, text)));
        http.post()
            .uri(settings.url())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(body)
            .retrieve()
            .toBodilessEntity();
      }
    } catch (RestClientException e) {
      // The exception's class only: its message may echo the number or the text.
      throw new MessageSenders.SendFailed(code, e.getClass().getSimpleName());
    }
  }

  private String fill(String template, String phone, String text) {
    return template
        .replace("{to}", phone)
        .replace("{from}", settings.from())
        .replace("{text}", text);
  }
}
