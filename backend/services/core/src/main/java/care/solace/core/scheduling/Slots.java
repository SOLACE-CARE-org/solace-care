package care.solace.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Session starts from weekly hours, computed on read (System Design, Scheduling). Within each range
 * sessions follow one another with the break between them; a start must leave the minimum notice
 * and the whole session must fit in the range. Times are local to the specialist's time zone, so
 * daylight-saving changes fall where they should. Bookings, holds and time off are taken off by
 * {@link Scheduling}.
 */
final class Slots {

  /** How far ahead "next available" looks. Not in the specs: a working default. */
  static final int HORIZON_DAYS = 14;

  private Slots() {}

  record Interval(Instant start, Instant end) {

    Interval withEnd(Instant newEnd) {
      return new Interval(start, newEnd);
    }

    boolean overlaps(Instant otherStart, Instant otherEnd) {
      return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
  }

  /** Every start from {@code from} for {@code days} days, after the notice from {@code now}. */
  static List<Interval> starts(
      WeeklyHoursStore.Schedule schedule, LocalDate from, int days, int minutes, Instant now) {
    List<Interval> starts = new ArrayList<>();
    if (minutes <= 0) {
      return starts;
    }
    ZoneId zone = ZoneId.of(schedule.timeZone());
    Instant earliest = now.plus(Duration.ofHours(schedule.minimumNoticeHours()));
    Duration session = Duration.ofMinutes(minutes);
    Duration step = session.plusMinutes(schedule.breakMinutes());
    for (int day = 0; day < days; day++) {
      LocalDate date = from.plusDays(day);
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
            starts.add(new Interval(start.toInstant(), start.plus(session).toInstant()));
          }
        }
      }
    }
    return starts;
  }

  /** The first start in the next two weeks, before bookings, holds and time off. */
  static Optional<Instant> next(WeeklyHoursStore.Schedule schedule, Instant now, int minutes) {
    LocalDate today = LocalDate.ofInstant(now, ZoneId.of(schedule.timeZone()));
    return starts(schedule, today, HORIZON_DAYS + 1, minutes, now).stream()
        .map(Interval::start)
        .findFirst();
  }
}
