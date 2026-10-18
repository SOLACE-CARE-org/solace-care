package care.solace.core.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Expo's push service (D38), which delivers through APNs and FCM. The message is the body only: no
 * title, so a lock screen shows nothing more than the copy allows.
 */
class ExpoPushSender implements PushSender {

  private static final Logger LOG = LoggerFactory.getLogger(ExpoPushSender.class);

  private final RestClient http;

  ExpoPushSender(RestClient.Builder builder, String url, String accessToken) {
    var b = builder.baseUrl(url).defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
    if (accessToken != null && !accessToken.isBlank()) {
      b = b.defaultHeader("Authorization", "Bearer " + accessToken);
    }
    this.http = b.build();
  }

  @Override
  public Set<String> send(List<String> tokens, String text, Map<String, String> data) {
    Set<String> gone = new HashSet<>();
    if (tokens.isEmpty()) {
      return gone;
    }
    List<Map<String, Object>> messages =
        tokens.stream()
            .map(
                to -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("to", to);
                  m.put("body", text);
                  m.put("data", data);
                  m.put("sound", "default");
                  return m;
                })
            .toList();
    try {
      JsonNode reply =
          http.post()
              .uri("/--/api/v2/push/send")
              .contentType(MediaType.APPLICATION_JSON)
              .body(messages)
              .retrieve()
              .body(JsonNode.class);
      JsonNode tickets = reply == null ? null : reply.path("data");
      for (int i = 0; tickets != null && i < tickets.size() && i < tokens.size(); i++) {
        if ("DeviceNotRegistered".equals(tickets.get(i).path("details").path("error").asText())) {
          gone.add(tokens.get(i));
        }
      }
    } catch (RestClientException e) {
      LOG.warn("Push could not be sent to {} device(s)", tokens.size(), e);
    }
    return gone;
  }
}
