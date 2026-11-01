package care.solace.keycloak.otp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Default codes for testing (D63). Off unless {@code SOLACE_TEST_CODE} is set here and Core says a
 * Super admin has turned the code on. If Core can't be asked, the code is refused.
 */
public final class TestCodes {

  private static final Logger LOG = Logger.getLogger(TestCodes.class);
  // Not in the specs: a switch change reaches sign-in within seconds without a call per page.
  private static final Duration REMEMBER = Duration.ofSeconds(10);
  private static final String CODE = System.getenv("SOLACE_TEST_CODE");
  private static final Clock CLOCK = Clock.systemUTC();

  private static volatile Map<?, ?> state = Map.of();
  private static volatile Instant asked = Instant.EPOCH;

  private TestCodes() {}

  public static boolean acceptsSignIn(String candidate) {
    return matches(candidate) && on("signIn");
  }

  public static boolean acceptsStaff(String candidate) {
    return matches(candidate) && on("staff");
  }

  public static boolean staffOn() {
    return CODE != null && !CODE.isBlank() && on("staff");
  }

  private static boolean matches(String candidate) {
    return CODE != null
        && !CODE.isBlank()
        && candidate != null
        && MessageDigest.isEqual(
            CODE.strip().getBytes(StandardCharsets.UTF_8),
            candidate.strip().getBytes(StandardCharsets.UTF_8));
  }

  private static boolean on(String key) {
    return Boolean.TRUE.equals(current().get(key));
  }

  private static synchronized Map<?, ?> current() {
    Instant now = CLOCK.instant();
    if (now.isAfter(asked.plus(REMEMBER))) {
      asked = now;
      try {
        state = CoreCodeSender.instance().testCodes();
      } catch (IOException | RuntimeException e) {
        LOG.warnf("Could not ask Core about test codes: %s", e.getClass().getSimpleName());
        state = Map.of();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        state = Map.of();
      }
    }
    return state;
  }
}
