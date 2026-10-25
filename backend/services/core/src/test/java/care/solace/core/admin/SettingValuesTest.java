package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import care.solace.errors.SolaceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Lists, a phone number and codes as settings (D61): checked and written one way. */
class SettingValuesTest {

  private final SettingCatalogue catalogue = new SettingCatalogue(new MockEnvironment());

  private SettingCatalogue.Definition d(String key) {
    return catalogue.setting(key).orElseThrow();
  }

  @Test
  void sessionLengthsAreDistinctWholeMinutesInRangeAndSorted() {
    var lengths = d("solace.specialists.pricing.durations");
    assertThat(Settings.normalise(lengths, " 80, 30 ,50")).isEqualTo("30,50,80");
    assertThatThrownBy(() -> Settings.normalise(lengths, "30,30"))
        .isInstanceOf(SolaceException.class);
    assertThatThrownBy(() -> Settings.normalise(lengths, "5")).isInstanceOf(SolaceException.class);
    assertThatThrownBy(() -> Settings.normalise(lengths, "")).isInstanceOf(SolaceException.class);
    assertThatThrownBy(() -> Settings.normalise(lengths, "thirty"))
        .isInstanceOf(SolaceException.class);
  }

  @Test
  void theDialInNumberIsInternationalOrEmpty() {
    var number = d("solace.sessions.dial-in.number");
    assertThat(Settings.normalise(number, "+237222000111")).isEqualTo("+237222000111");
    assertThat(Settings.normalise(number, "")).isEmpty();
    assertThatThrownBy(() -> Settings.normalise(number, "222000111"))
        .isInstanceOf(SolaceException.class);
  }

  @Test
  void offeredConsentsComeFromTheAllowedList() {
    var purposes = d("solace.privacy.offered-purposes");
    assertThat(Settings.normalise(purposes, "AI_SCRIBE, AI_ASSIST"))
        .isEqualTo("AI_SCRIBE,AI_ASSIST");
    assertThat(Settings.normalise(purposes, "")).isEmpty();
    // Tracking and the health check are offered by their flags, not here.
    assertThatThrownBy(() -> Settings.normalise(purposes, "TRACKING"))
        .isInstanceOf(SolaceException.class);
  }
}
