package care.solace.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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
    return new Consents(null, SETTINGS, event -> {}, Clock.systemUTC(), on::contains);
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
