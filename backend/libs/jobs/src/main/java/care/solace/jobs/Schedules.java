package care.solace.jobs;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.scheduling.support.CronExpression;

/** Reading and checking schedules as job rows and {@link Job} write them. */
final class Schedules {

  /** Shorter than this would load the database with run records. */
  static final Duration SHORTEST = Duration.ofSeconds(5);

  private Schedules() {}

  /** An ISO-8601 duration ({@code PT30S}) or whole milliseconds, as {@code @Scheduled} took. */
  static Duration interval(String value) {
    String v = value.strip();
    if (v.chars().allMatch(Character::isDigit) && !v.isEmpty()) {
      return Duration.ofMillis(Long.parseLong(v));
    }
    return Duration.parse(v);
  }

  /** True when the schedule can run: a valid cron and zone, or an interval of at least 5 s. */
  static boolean valid(JobTriggerStore.Kind kind, String schedule, String zone) {
    if (schedule == null || schedule.isBlank()) {
      return false;
    }
    try {
      if (kind == JobTriggerStore.Kind.INTERVAL) {
        return zone == null && interval(schedule).compareTo(SHORTEST) >= 0;
      }
      CronExpression.parse(schedule);
      if (zone != null) {
        ZoneId.of(zone);
      }
      return true;
    } catch (IllegalArgumentException | DateTimeException e) {
      return false;
    }
  }
}
