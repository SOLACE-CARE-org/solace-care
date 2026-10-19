package care.solace.ai.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Embeddings through an OpenAI-compatible {@code /embeddings} endpoint (System Design, Models). */
final class HttpEmbeddingRoute implements EmbeddingRoute {

  private final GatewaySettings.Model settings;
  private final RestClient http;

  HttpEmbeddingRoute(GatewaySettings.Model settings) {
    this.settings = settings;
    var builder = RestClient.builder().baseUrl(settings.url());
    if (!settings.apiKey().isBlank()) {
      builder.defaultHeader("Authorization", "Bearer " + settings.apiKey());
    }
    this.http = builder.build();
  }

  @Override
  public String name() {
    return settings.name();
  }

  @Override
  public boolean local() {
    return settings.local();
  }

  @Override
  public float[] embed(String text) {
    Map<?, ?> body =
        http.post()
            .uri("/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("model", settings.model(), "input", text))
            .retrieve()
            .body(Map.class);
    if (body == null || !(body.get("data") instanceof List<?> data) || data.isEmpty()) {
      return new float[0];
    }
    if (!(data.getFirst() instanceof Map<?, ?> first)
        || !(first.get("embedding") instanceof List<?> values)) {
      return new float[0];
    }
    float[] out = new float[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ((Number) values.get(i)).floatValue();
    }
    return out;
  }
}
