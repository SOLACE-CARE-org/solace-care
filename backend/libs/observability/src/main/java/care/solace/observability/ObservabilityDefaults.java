package care.solace.observability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Where telemetry goes and how much is kept, at the lowest precedence. Locally everything goes to
 * the collector in infra/docker-compose.yml; environments set {@code SOLACE_OTLP_ENDPOINT}.
 *
 * <p>The sampling rate is a setting ({@code management.tracing.sampling.probability}); every trace
 * is kept by default, which suits local work and the pilot. It is not in the specs.
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String otlp = "${SOLACE_OTLP_ENDPOINT:http://localhost:4318}";
    Map<String, Object> defaults = new HashMap<>();
    defaults.put("management.tracing.sampling.probability", "1.0");
    defaults.put("management.otlp.tracing.endpoint", otlp + "/v1/traces");
    defaults.put("management.otlp.logging.endpoint", otlp + "/v1/logs");
    defaults.put("management.otlp.metrics.export.url", otlp + "/v1/metrics");
    defaults.put("management.otlp.metrics.export.step", "30s");
    defaults.put(
        "management.opentelemetry.resource-attributes.deployment.environment",
        "${SOLACE_ENVIRONMENT:local}");
    // Carry the trace across reactive and async boundaries (the gateway, Kafka listeners).
    defaults.put("spring.reactor.context-propagation", "auto");
    defaults.put("spring.grpc.client.observation.enabled", "true");
    defaults.put("spring.grpc.server.observation.enabled", "true");
    environment
        .getPropertySources()
        .addLast(new MapPropertySource("solaceObservabilityDefaults", defaults));
  }
}
