package care.solace.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.core.admin.PlatformSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tracking and health check consent appear with their Phase 2 flags (D52). */
class ConsentsOfferTest {

  private static final PrivacySettings SETTINGS =
      new PrivacySettings(
          List.of(Purpose.AI_ASSIST, Purpose.AI_SCRIBE),
          null,
          "solace-exports",
          Duration.ofDays(7),
          Duration.ofDays(30),
          Duration.ofDays(365),
          Duration.ofDays(180));

  private static Consents with(Set<String> on) {
    return with(on, null);
  }

  /** {@code offered}: the setting as staff set it on screen 20 (D61), or null for the default. */
  private static Consents with(Set<String> on, List<String> offered) {
    return new Consents(
        settings(offered), null, SETTINGS, event -> {}, Clock.systemUTC(), on::contains);
  }

  private static PlatformSettings settings(List<String> offered) {
    return new PlatformSettings() {
      @Override
      public Duration duration(String key, Duration fallback) {
        return fallback;
      }

      @Override
      public long number(String key, long fallback) {
        return fallback;
      }

      @Override
      public BigDecimal decimal(String key, BigDecimal fallback) {
        return fallback;
      }

      @Override
      public LocalTime time(String key, LocalTime fallback) {
        return fallback;
      }

      @Override
      public List<Long> numbers(String key, List<Long> fallback) {
        return fallback;
      }

      @Override
      public String text(String key, String fallback) {
        return fallback;
      }

      @Override
      public List<String> codes(String key, List<String> fallback) {
        return offered == null ? fallback : offered;
      }
    };
  }

  @Test
  void staffChooseWhichAiConsentsTheAppAsksAbout() {
    assertThat(with(Set.of("trackTab"), List.of("AI_SCRIBE")).offered())
        .containsExactly(Purpose.TRACKING, Purpose.AI_SCRIBE);
  }

  @Test
  void phaseOneAsksOnlyAboutAi() {
    assertThat(with(Set.of()).offered()).containsExactly(Purpose.AI_ASSIST, Purpose.AI_SCRIBE);
  }

  @Test
  void theTrackTabBringsTheTrackingSwitchFirst() {
    assertThat(with(Set.of("trackTab")).offered())
        .containsExactly(Purpose.TRACKING, Purpose.AI_ASSIST, Purpose.AI_SCRIBE);
  }

  @Test
  void theHealthCheckBringsItsOwnSwitch() {
    assertThat(with(Set.of("trackTab", "healthCheck")).offered())
        .containsExactly(
            Purpose.TRACKING, Purpose.AI_ASSIST, Purpose.AI_SCRIBE, Purpose.HEALTH_CHECK);
  }
}
