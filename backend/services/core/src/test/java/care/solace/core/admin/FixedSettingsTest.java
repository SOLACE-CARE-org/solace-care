package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Fixed decisions are shown on Settings and flags but live as constants in code (D61). This keeps
 * the screen honest: each value it shows is the one the code uses.
 */
class FixedSettingsTest {

  private final SettingCatalogue catalogue = new SettingCatalogue(new MockEnvironment());

  /** Setting key to the class and constant that decide it. */
  private static final Map<String, String> CONSTANTS =
      Map.of(
          "solace.staff.temporary-password", "care.solace.core.admin.Team#PASSWORD_VALIDITY",
          "solace.sessions.join-opens", "care.solace.core.sessions.Sessions#JOIN_OPENS",
          "solace.sessions.token-after-end", "care.solace.core.sessions.Sessions#TOKEN_AFTER_END",
          "solace.sessions.reconnect", "care.solace.core.sessions.LiveKit#RECONNECT",
          "solace.identity.minimum-age", "care.solace.core.identity.Signups#MINIMUM_AGE",
          "solace.specialists.licence-reminder",
              "care.solace.core.specialists.LicenceRenewals#DAYS_BEFORE");

  @Test
  void everyFixedValueOnTheScreenIsTheOneTheCodeUses() throws Exception {
    for (var entry : CONSTANTS.entrySet()) {
      var definition = catalogue.setting(entry.getKey()).orElseThrow();
      assertThat(definition.editable()).as(entry.getKey()).isFalse();
      long shown = Long.parseLong(definition.fallback());
      assertThat(definition.min()).as(entry.getKey()).isEqualTo(shown);
      assertThat(definition.max()).as(entry.getKey()).isEqualTo(shown);
      assertThat(inUnits(constant(entry.getValue()), definition.type()))
          .as(entry.getKey())
          .isEqualTo(shown);
    }
  }

  private static Object constant(String reference) throws Exception {
    String[] parts = reference.split("#");
    Field field = Class.forName(parts[0]).getDeclaredField(parts[1]);
    field.setAccessible(true);
    return field.get(null);
  }

  private static long inUnits(Object value, SettingCatalogue.Type type) {
    if (value instanceof Duration d) {
      return type.fromDuration(d);
    }
    return ((Number) value).longValue();
  }
}
