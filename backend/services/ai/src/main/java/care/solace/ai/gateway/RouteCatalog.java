package care.solace.ai.gateway;

import java.util.List;
import org.springframework.stereotype.Component;

/** The models the gateway may use, from configuration. Tests put their own in its place. */
@Component
public class RouteCatalog {

  private final List<ModelRoute> routes;

  @org.springframework.beans.factory.annotation.Autowired
  RouteCatalog(GatewaySettings settings) {
    this.routes = settings.models().stream().map(m -> (ModelRoute) new SpringAiRoute(m)).toList();
  }

  protected RouteCatalog(List<ModelRoute> routes) {
    this.routes = routes;
  }

  public List<ModelRoute> routes() {
    return routes;
  }
}
