package care.solace.grpc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Circuit breaker settings ({@code solace.grpc.breaker.*}). None of these numbers is in the specs,
 * so they are settings with defaults, not decisions.
 *
 * @param slidingWindowSize calls looked at when deciding to open
 * @param minimumNumberOfCalls calls needed before the breaker may open
 * @param failureRateThreshold percentage of failing calls that opens the breaker
 * @param waitInOpenState how long the breaker stays open before trying again
 * @param permittedCallsInHalfOpenState trial calls while half open
 */
@ConfigurationProperties("solace.grpc.breaker")
public record BreakerSettings(
    @DefaultValue("20") int slidingWindowSize,
    @DefaultValue("10") int minimumNumberOfCalls,
    @DefaultValue("50") float failureRateThreshold,
    @DefaultValue("10s") Duration waitInOpenState,
    @DefaultValue("3") int permittedCallsInHalfOpenState) {

  public static BreakerSettings defaults() {
    return new BreakerSettings(20, 10, 50f, Duration.ofSeconds(10), 3);
  }
}
