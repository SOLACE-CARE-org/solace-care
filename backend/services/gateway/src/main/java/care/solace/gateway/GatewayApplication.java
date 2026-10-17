package care.solace.gateway;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

/**
 * The gateway: {@code /api/core/**}, {@code /api/tracking/**} and {@code /api/ai/**} go to their
 * service with the {@code /api/<name>} prefix removed and the caller's token passed on, because
 * every service checks the token again.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewaySettings.class)
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }

  /** Token bucket per caller; see {@link RateLimitFilter}. */
  @Bean
  RedisRateLimiter redisRateLimiter(GatewaySettings settings) {
    return new RedisRateLimiter(
        settings.rateLimit().replenishRate(),
        settings.rateLimit().burstCapacity(),
        settings.rateLimit().requestedTokens());
  }

  @Bean
  RouteLocator routes(RouteLocatorBuilder builder, GatewaySettings settings) {
    RouteLocatorBuilder.Builder routes = builder.routes();
    for (Map.Entry<String, URI> service : settings.services().entrySet()) {
      String name = service.getKey();
      routes.route(
          name,
          route ->
              route
                  .path("/api/" + name + "/**")
                  .filters(filters -> filters.stripPrefix(2))
                  .uri(service.getValue()));
    }
    return routes.build();
  }
}
