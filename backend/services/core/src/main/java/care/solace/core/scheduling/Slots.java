package care.solace.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;

/**
 * Session starts from weekly hours, computed on read (System Design, Scheduling). Within each range
 * sessions follow one another with the break between them; a start must leave the minimum notice
 * and the whole session must fit in the range. Times are local to the specialist's time zone, so
 * daylight-saving changes fall where they should.
 *
 * <p>Bookings and holds are not subtracted yet: they arrive with epic 4.
 */
final class Slots {

  /** How far ahead "next available" looks. Not in the specs: a working default. */
  static final int HORIZON_DAYS = 14;

  private Slots() {}

  static Optional<Instant> next(WeeklyHoursStore.Schedule schedule, Instant now, int minutes) {
    if (minutes <= 0) {
      return Optional.empty();
    }
    ZoneId zone = ZoneId.of(schedule.timeZone());
    Instant earliest = now.plus(Duration.ofHours(schedule.minimumNoticeHours()));
    Duration session = Duration.ofMinutes(minutes);
    Duration step = session.plusMinutes(schedule.breakMinutes());
    LocalDate today = LocalDate.ofInstant(now, zone);
    for (int day = 0; day <= HORIZON_DAYS; day++) {
      LocalDate date = today.plusDays(day);
      var rules =
          schedule.rules().stream()
              .filter(r -> r.weekday() == date.getDayOfWeek())
              .sorted(Comparator.comparing(WeeklyHoursStore.Rule::start))
              .toList();
      for (var rule : rules) {
        ZonedDateTime end = date.atTime(rule.end()).atZone(zone);
        for (ZonedDateTime start = date.atTime(rule.start()).atZone(zone);
            !start.plus(session).isAfter(end);
            start = start.plus(step)) {
          if (!start.toInstant().isBefore(earliest)) {
            return Optional.of(start.toInstant());
          }
        }
      }
    }
    return Optional.empty();
  }
}
