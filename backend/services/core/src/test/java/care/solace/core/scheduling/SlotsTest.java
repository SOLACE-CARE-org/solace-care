package care.solace.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotsTest {

  private static final String DOUALA = "Africa/Douala"; // UTC+1, no daylight saving

  private static WeeklyHoursStore.Schedule hours(
      int breakMinutes, int noticeHours, WeeklyHoursStore.Rule... rules) {
    return new WeeklyHoursStore.Schedule(DOUALA, breakMinutes, noticeHours, List.of(rules));
  }

  private static WeeklyHoursStore.Rule rule(DayOfWeek day, String start, String end) {
    return new WeeklyHoursStore.Rule(day, LocalTime.parse(start), LocalTime.parse(end));
  }

  @Test
  void theFirstStartAfterTheNoticeThatFitsTheSession() {
    // Monday 19 Oct 2026, 08:30 in Douala.
    Instant now = Instant.parse("2026-10-19T07:30:00Z");
    var schedule = hours(10, 2, rule(DayOfWeek.MONDAY, "09:00", "12:00"));

    // Sessions step by 50 + 10 from 09:00: 09:00, 10:00, 11:00 (ends 11:50). The notice runs
    // to 10:30, so 11:00 local (10:00 UTC) is the first.
    assertThat(Slots.next(schedule, now, 50)).contains(Instant.parse("2026-10-19T10:00:00Z"));
  }

  @Test
  void movesToTheNextDayWithHoursWhenTodayIsFull() {
    Instant now = Instant.parse("2026-10-19T10:30:00Z"); // Monday 11:30 local
    var schedule =
        hours(
            0,
            2,
            rule(DayOfWeek.MONDAY, "09:00", "12:00"),
            rule(DayOfWeek.THURSDAY, "16:00", "19:00"));

    assertThat(Slots.next(schedule, now, 50)).contains(Instant.parse("2026-10-22T15:00:00Z"));
  }

  @Test
  void nothingWhenNoRangeFitsTheSession() {
    Instant now = Instant.parse("2026-10-19T07:30:00Z");
    var schedule = hours(0, 2, rule(DayOfWeek.MONDAY, "09:00", "09:30"));

    assertThat(Slots.next(schedule, now, 50)).isEmpty();
    assertThat(Slots.next(hours(0, 2), now, 50)).isEmpty();
  }
}
