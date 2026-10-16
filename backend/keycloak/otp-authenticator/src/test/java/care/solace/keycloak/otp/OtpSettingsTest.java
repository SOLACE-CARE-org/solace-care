package care.solace.keycloak.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtpSettingsTest {

  @Test
  void defaultsMatchThePlaceholdersInTheSpecs() {
    OtpSettings settings = OtpSettings.from(null);

    // notification-copy.md: the code "expires in [10] min".
    assertThat(settings.codeTtl()).isEqualTo(Duration.ofMinutes(10));
    assertThat(settings.codeLength()).isEqualTo(6);
  }

  @Test
  void readsValuesFromTheRealmConfig() {
    OtpSettings settings =
        OtpSettings.from(
            Map.of(
                OtpSettings.CODE_LENGTH, "8",
                OtpSettings.CODE_TTL_SECONDS, "300",
                OtpSettings.MAX_ATTEMPTS, "3",
                OtpSettings.RESEND_AFTER_SECONDS, "30",
                OtpSettings.SENDER, "logging"));

    assertThat(settings)
        .isEqualTo(new OtpSettings(8, Duration.ofMinutes(5), 3, Duration.ofSeconds(30), "logging"));
  }

  @Test
  void blankValuesFallBackToDefaults() {
    assertThat(OtpSettings.from(Map.of(OtpSettings.CODE_LENGTH, " ")))
        .isEqualTo(OtpSettings.DEFAULTS);
  }

  @Test
  void rejectsNonsense() {
    assertThatThrownBy(() -> OtpSettings.from(Map.of(OtpSettings.CODE_LENGTH, "2")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OtpSettings.from(Map.of(OtpSettings.MAX_ATTEMPTS, "0")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
