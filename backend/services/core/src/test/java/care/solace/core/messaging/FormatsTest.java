package care.solace.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Placeholders and quiet hours (D38). */
class FormatsTest {

  @Test
  void frenchPlaceholdersLoseOnlyTheAccentsSmsCannotCarry() {
    assertThat(Formats.gsmSafe("août, déc., Hôpital")).isEqualTo("aout, déc., Hopital");
    assertThat(Formats.amount(15000, Locale.FRENCH)).isEqualTo("15 000");
    assertThat(Formats.amount(15000, Locale.ENGLISH)).isEqualTo("15,000");
  }

  @Test
  void quietHoursHoldMessagesThatCanWaitUntilMorning() {
    ZoneId douala = ZoneId.of("Africa/Douala");
    LocalTime start = LocalTime.of(21, 0);
    LocalTime end = LocalTime.of(7, 0);
    Instant evening = Instant.parse("2026-10-18T21:30:00Z"); // 22:30 in Douala
    assertThat(Notifier.QuietHours.next(evening, douala, start, end))
        .isEqualTo(Instant.parse("2026-10-19T06:00:00Z"));
    Instant early = Instant.parse("2026-10-19T04:00:00Z"); // 05:00
    assertThat(Notifier.QuietHours.next(early, douala, start, end))
        .isEqualTo(Instant.parse("2026-10-19T06:00:00Z"));
    Instant noon = Instant.parse("2026-10-19T11:00:00Z");
    assertThat(Notifier.QuietHours.next(noon, douala, start, end)).isEqualTo(noon);
  }
}
