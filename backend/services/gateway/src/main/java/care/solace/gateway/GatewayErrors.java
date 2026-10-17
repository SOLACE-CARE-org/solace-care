package care.solace.gateway;

import care.solace.errors.CommonError;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Errors raised in the gateway itself, as problem responses. A service that is down or too slow is
 * {@code 503 dependency.unavailable} (System Design availability target: the app shows its error
 * screen and the person can retry), never a bare 500.
 */
@Component
@Order(-2)
class GatewayErrors implements ErrorWebExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GatewayErrors.class);

  private final ProblemWriter problems;

  GatewayErrors(ProblemWriter problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(error);
    }
    CommonError mapped = map(error);
    if (mapped == CommonError.DEPENDENCY_UNAVAILABLE) {
      LOG.warn(
          "Service unavailable for {}: {}", routeOf(exchange), error.getClass().getSimpleName());
    } else if (mapped == CommonError.INTERNAL) {
      LOG.error("Gateway error for {}", routeOf(exchange), error);
    }
    return problems.write(exchange, mapped);
  }

  static CommonError map(Throwable error) {
    for (Throwable e = error; e != null; e = e.getCause()) {
      if (e instanceof ConnectException
          || e instanceof TimeoutException
          || e instanceof io.netty.channel.ConnectTimeoutException) {
        return CommonError.DEPENDENCY_UNAVAILABLE;
      }
      if (e instanceof ResponseStatusException status) {
        int code = status.getStatusCode().value();
        return code == 504 || code == 503
            ? CommonError.DEPENDENCY_UNAVAILABLE
            : CommonError.forStatus(code);
      }
    }
    return CommonError.INTERNAL;
  }

  private static String routeOf(ServerWebExchange exchange) {
    return exchange.getRequest().getPath().value().split("/").length > 2
        ? "/api/" + exchange.getRequest().getPath().value().split("/")[2]
        : exchange.getRequest().getPath().value();
  }
}
