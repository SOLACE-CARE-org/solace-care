package care.solace.grpc;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs gRPC calls behind a circuit breaker per callee, with a fallback the caller must define (D2:
 * "a circuit breaker and a defined fallback for every call"). The System Design lists each call's
 * fallback, e.g. "brief is generated without tracking data", "specialist access denied".
 *
 * <pre>{@code
 * boolean live = calls.call("core", () -> bookings.checkLiveBooking(request).getLive(), e -> false);
 * }</pre>
 *
 * <p>The fallback runs on any failure, including an open breaker. Only signs that the callee is
 * unwell (unavailable, deadline exceeded, overloaded, internal error) count towards opening the
 * breaker; an answer like "not found" or "permission denied" does not.
 */
public class ResilientCalls {

  private static final Logger LOG = LoggerFactory.getLogger(ResilientCalls.class);

  /** Status codes that say the callee is unwell rather than answering. */
  static final Set<Status.Code> CALLEE_UNWELL =
      EnumSet.of(
          Status.Code.UNAVAILABLE,
          Status.Code.DEADLINE_EXCEEDED,
          Status.Code.RESOURCE_EXHAUSTED,
          Status.Code.INTERNAL,
          Status.Code.UNKNOWN);

  private final CircuitBreakerRegistry breakers;

  public ResilientCalls(CircuitBreakerRegistry breakers) {
    this.breakers = breakers;
  }

  /** A breaker config with the given settings that only counts the callee being unwell. */
  public static CircuitBreakerConfig breakerConfig(BreakerSettings settings) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(settings.slidingWindowSize())
        .minimumNumberOfCalls(settings.minimumNumberOfCalls())
        .failureRateThreshold(settings.failureRateThreshold())
        .waitDurationInOpenState(settings.waitInOpenState())
        .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
        .recordException(ResilientCalls::calleeUnwell)
        .build();
  }

  /**
   * Calls {@code callee}; on any failure returns {@code fallback} applied to the failure.
   *
   * @param callee breaker name, one per called service: {@code core}, {@code tracking}, {@code ai}
   */
  public <T> T call(String callee, Supplier<T> call, Function<Throwable, T> fallback) {
    Objects.requireNonNull(fallback, "every gRPC call needs a fallback");
    CircuitBreaker breaker = breakers.circuitBreaker(callee);
    try {
      return breaker.executeSupplier(call);
    } catch (RuntimeException e) {
      LOG.warn("gRPC call to {} failed, using fallback: {}", callee, describe(e));
      return fallback.apply(e);
    }
  }

  static boolean calleeUnwell(Throwable e) {
    return e instanceof StatusRuntimeException s && CALLEE_UNWELL.contains(s.getStatus().getCode());
  }

  private static String describe(Throwable e) {
    if (e instanceof CallNotPermittedException) {
      return "circuit open";
    }
    if (e instanceof StatusRuntimeException s) {
      return s.getStatus().getCode().name();
    }
    return e.getClass().getSimpleName();
  }
}
