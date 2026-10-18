package care.solace.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A self-hosted model behind an OpenAI-compatible chat endpoint (vLLM, Ollama), so no text leaves
 * Solace's hosting (AI-1). Off until the model is chosen and passes the recall gate (D39). It
 * answers with one category name or NONE; anything else, or no answer, counts as no flag from the
 * model, and the phrase classifier still runs beside it.
 */
final class ModelClassifier implements SafetyClassifier {

  private static final Logger LOG = LoggerFactory.getLogger(ModelClassifier.class);
  private static final String PROMPT =
      "You check short texts written by people using a mental-health service for crisis risk."
          + " Answer with exactly one word: SUICIDAL_IDEATION, SELF_HARM, VIOLENT_THOUGHTS,"
          + " SUBSTANCE_CRISIS, RISK_TAKING, ACUTE_ANXIETY, or NONE. When unsure, choose the"
          + " category. Texts may mix English and French.";

  private final RestClient http;
  private final String model;

  ModelClassifier(RestClient.Builder builder, String url, String model) {
    this.http = builder.baseUrl(url).build();
    this.model = model;
  }

  @Override
  public String version() {
    return "model:" + model;
  }

  @Override
  public Optional<Category> classify(String text, Locale locale) {
    try {
      JsonNode reply =
          http.post()
              .uri("/v1/chat/completions")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "model",
                      model,
                      "temperature",
                      0,
                      "messages",
                      List.of(
                          Map.of("role", "system", "content", PROMPT),
                          Map.of("role", "user", "content", text))))
              .retrieve()
              .body(JsonNode.class);
      String answer =
          reply == null
              ? ""
              : reply.path("choices").path(0).path("message").path("content").asText("").strip();
      for (Category c : Category.values()) {
        if (answer.toUpperCase(Locale.ROOT).startsWith(c.name())) {
          return Optional.of(c);
        }
      }
    } catch (RestClientException e) {
      // The text is never logged.
      LOG.warn("Safety model unavailable; the phrase classifier still decides");
    }
    return Optional.empty();
  }
}
