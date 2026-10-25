package care.solace.ai.gateway;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The models the gateway may use: the AI model rows (D61), read from {@link ModelRegistry}. Tests
 * put fixed lists in its place; their routes then come from {@code solace.ai.routes}.
 */
@Component
public class RouteCatalog {

  private final ModelRegistry registry;
  private final List<ModelRoute> routes;
  private final List<SpeechRoute> speech;
  private final List<EmbeddingRoute> embeddings;

  @org.springframework.beans.factory.annotation.Autowired
  RouteCatalog(ModelRegistry registry) {
    this.registry = registry;
    this.routes = null;
    this.speech = null;
    this.embeddings = null;
  }

  protected RouteCatalog(List<ModelRoute> routes) {
    this(routes, List.of(), List.of());
  }

  protected RouteCatalog(
      List<ModelRoute> routes, List<SpeechRoute> speech, List<EmbeddingRoute> embeddings) {
    this.registry = null;
    this.routes = routes;
    this.speech = speech;
    this.embeddings = embeddings;
  }

  public List<ModelRoute> routes() {
    return registry == null ? routes : registry.chat();
  }

  public List<SpeechRoute> speech() {
    return registry == null ? speech : registry.speech();
  }

  public List<EmbeddingRoute> embeddings() {
    return registry == null ? embeddings : registry.embeddings();
  }

  /** The feature's live route from the rows; empty for fixed lists. */
  Optional<List<String>> routeNames(Feature feature) {
    return registry == null ? Optional.empty() : Optional.of(registry.route(feature));
  }

  /** The feature's monthly budget when a Super admin set one. */
  Optional<Long> budget(Feature feature) {
    return registry == null ? Optional.empty() : registry.budget(feature);
  }
}
