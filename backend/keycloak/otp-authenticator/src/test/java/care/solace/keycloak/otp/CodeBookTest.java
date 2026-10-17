package care.solace.keycloak.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeBookTest {

  private static final OtpSettings SETTINGS =
      new OtpSettings(6, Duration.ofMinutes(10), 3, Duration.ofSeconds(60), "logging");

  private final MemoryStore store = new MemoryStore();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T10:00:00Z"));
  private final CodeBook codes = new CodeBook(store, SETTINGS, clock, "realm-1");

  @Test
  void rightCodeIsAcceptedOnce() {
    String code = codes.issue("user-1").orElseThrow();

    assertThat(code).hasSize(6).containsOnlyDigits();
    assertThat(codes.verify("user-1", code).result()).isEqualTo(CodeBook.Result.ACCEPTED);
    assertThat(codes.verify("user-1", code).result()).isEqualTo(CodeBook.Result.NO_LIVE_CODE);
  }

  @Test
  void storesOnlyAHashOfTheCode() {
    String code = codes.issue("user-1").orElseThrow();

    assertThat(store.entries.values())
        .singleElement()
        .satisfies(entry -> assertThat(entry.values()).doesNotContain(code));
  }

  @Test
  void codeExpiresAfterItsLifetime() {
    String code = codes.issue("user-1").orElseThrow();
    clock.advance(Duration.ofMinutes(10));

    assertThat(codes.verify("user-1", code).result()).isEqualTo(CodeBook.Result.NO_LIVE_CODE);
  }

  @Test
  void tooManyWrongAttemptsDiscardTheCode() {
    String code = codes.issue("user-1").orElseThrow();

    assertThat(codes.verify("user-1", wrong(code)))
        .isEqualTo(new CodeBook.Check(CodeBook.Result.WRONG, 2));
    assertThat(codes.verify("user-1", wrong(code)))
        .isEqualTo(new CodeBook.Check(CodeBook.Result.WRONG, 1));
    assertThat(codes.verify("user-1", wrong(code)))
        .isEqualTo(new CodeBook.Check(CodeBook.Result.EXHAUSTED, 0));
    assertThat(codes.verify("user-1", code).result()).isEqualTo(CodeBook.Result.NO_LIVE_CODE);
  }

  @Test
  void codeForOneUserDoesNotWorkForAnother() {
    String code = codes.issue("user-1").orElseThrow();
    codes.issue("user-2").orElseThrow();

    assertThat(codes.verify("user-2", code).result()).isNotEqualTo(CodeBook.Result.ACCEPTED);
  }

  @Test
  void noNewCodeBeforeTheResendDelay() {
    codes.issue("user-1").orElseThrow();
    clock.advance(Duration.ofSeconds(59));
    assertThat(codes.issue("user-1")).isEmpty();

    clock.advance(Duration.ofSeconds(1));
    assertThat(codes.issue("user-1")).isPresent();
  }

  @Test
  void newCodeReplacesTheOldOne() {
    String first = codes.issue("user-1").orElseThrow();
    clock.advance(Duration.ofSeconds(60));
    String second = codes.issue("user-1").orElseThrow();

    if (!first.equals(second)) {
      assertThat(codes.verify("user-1", first).result()).isEqualTo(CodeBook.Result.WRONG);
    }
    assertThat(codes.verify("user-1", second).result()).isEqualTo(CodeBook.Result.ACCEPTED);
  }

  private static String wrong(String code) {
    char last = code.charAt(code.length() - 1);
    return code.substring(0, code.length() - 1) + (last == '9' ? '0' : (char) (last + 1));
  }

  /** Ignores lifespans on purpose: CodeBook must enforce expiry itself. */
  private static final class MemoryStore implements CodeBook.Store {
    final Map<String, Map<String, String>> entries = new HashMap<>();

    @Override
    public Map<String, String> get(String key) {
      return entries.get(key);
    }

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
      entries.put(key, new HashMap<>(notes));
    }

    @Override
    public void replace(String key, Map<String, String> notes) {
      entries.put(key, new HashMap<>(notes));
    }

    @Override
    public void remove(String key) {
      entries.remove(key);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
