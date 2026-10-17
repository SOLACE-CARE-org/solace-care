package care.solace.gateway;

import care.solace.errors.CommonError;
import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Limits each caller with a token bucket in Redis (System Design: rate limits in Redis): per person
 * ({@code sub}) when signed in, per client address otherwise. Over the limit, the answer is a 429
 * problem. If Redis is down, Spring's limiter lets requests through rather than block care.
 */
@Component
class RateLimitFilter implements GlobalFilter, Ordered {

  static final String LIMITER_ID = "api";

  private final RedisRateLimiter limiter;
  private final ProblemWriter problems;

  RateLimitFilter(RedisRateLimiter limiter, ProblemWriter problems) {
    this.limiter = limiter;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return callerKey(exchange)
        .flatMap(key -> limiter.isAllowed(LIMITER_ID, key))
        .flatMap(
            response -> {
              response
                  .getHeaders()
                  .forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
              if (response.isAllowed()) {
                return chain.filter(exchange);
              }
              return problems.write(exchange, CommonError.RATE_LIMITED);
            });
  }

  private static Mono<String> callerKey(ServerWebExchange exchange) {
    return exchange
        .getPrincipal()
        .filter(Authentication.class::isInstance)
        .map(principal -> "user:" + principal.getName())
        .switchIfEmpty(Mono.fromSupplier(() -> "addr:" + clientAddress(exchange)));
  }

  /** The first address in X-Forwarded-For (set by the load balancer), else the socket's. */
  private static String clientAddress(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    return remote == null ? "unknown" : remote.getAddress().getHostAddress();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }
}
