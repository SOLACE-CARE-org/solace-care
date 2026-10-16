package care.solace.grpc;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * gRPC channel defaults, at the lowest precedence. gRPC keeps one long-lived HTTP/2 connection, so
 * Kubernetes' connection-level balancing would pin every call to one pod; channels balance per call
 * over all addresses of a headless service instead (System Design, Sync contracts).
 */
public class GrpcClientDefaults implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "solaceGrpcClientDefaults",
                Map.of(
                    "spring.grpc.client.default-channel.default-load-balancing-policy",
                    "round_robin",
                    "spring.grpc.client.default-channel.default-deadline",
                    DeadlineInterceptor.DEADLINE.toMillis() + "ms")));
  }
}
