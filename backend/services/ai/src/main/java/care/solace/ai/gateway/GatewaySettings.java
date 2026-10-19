package care.solace.ai.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The model gateway ({@code solace.ai.*}; D56).
 *
 * @param models every model the gateway may use
 * @param routes for each feature, model names in the order to try
 * @param budgets tokens a feature may use per calendar month ([1 000 000] each by default)
 * @param transferAuthorised true once Cameroon's data-transfer authorisation covers the non-local
 *     models; until then health data stays on local models (open question)
 * @param retentionDays prompts and outputs are deleted after this ([30] days)
 */
@ConfigurationProperties("solace.ai")
public record GatewaySettings(
    @DefaultValue List<Model> models,
    @DefaultValue Map<Feature, List<String>> routes,
    @DefaultValue Map<Feature, Long> budgets,
    @DefaultValue("false") boolean transferAuthorised,
    @DefaultValue("30") int retentionDays) {

  /**
   * @param name used in routes
   * @param url an OpenAI-compatible endpoint (vLLM, Ollama, or a hosted vendor)
   * @param model the model's name at that endpoint
   * @param apiKey empty for local endpoints
   * @param local hosted by Solace
   */
  public record Model(
      String name,
      String url,
      String model,
      @DefaultValue("") String apiKey,
      @DefaultValue("true") boolean local) {}

  long budget(Feature feature) {
    return budgets.getOrDefault(feature, 1_000_000L);
  }
}
