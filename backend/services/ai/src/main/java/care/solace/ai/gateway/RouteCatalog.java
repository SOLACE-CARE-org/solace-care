package care.solace.ai.gateway;

import java.util.List;
import org.springframework.stereotype.Component;

/** The models the gateway may use, from configuration. Tests put their own in its place. */
@Component
public class RouteCatalog {

  private final List<ModelRoute> routes;
  private final List<SpeechRoute> speech;
  private final List<EmbeddingRoute> embeddings;

  @org.springframework.beans.factory.annotation.Autowired
  RouteCatalog(GatewaySettings settings) {
    this.routes = settings.models().stream().map(m -> (ModelRoute) new SpringAiRoute(m)).toList();
    this.speech =
        settings.speech().stream().map(m -> (SpeechRoute) new HttpSpeechRoute(m)).toList();
    this.embeddings =
        settings.embeddings().stream()
            .map(m -> (EmbeddingRoute) new HttpEmbeddingRoute(m))
            .toList();
  }

  protected RouteCatalog(List<ModelRoute> routes) {
    this(routes, List.of(), List.of());
  }

  protected RouteCatalog(
      List<ModelRoute> routes, List<SpeechRoute> speech, List<EmbeddingRoute> embeddings) {
    this.routes = routes;
    this.speech = speech;
    this.embeddings = embeddings;
  }

  public List<ModelRoute> routes() {
    return routes;
  }

  public List<SpeechRoute> speech() {
    return speech;
  }

  public List<EmbeddingRoute> embeddings() {
    return embeddings;
  }
}
