package care.solace.core.scheduling;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code specialist_schedule} and {@code availability_rule}. */
@Repository
class WeeklyHoursStore {

  private final JdbcClient db;

  WeeklyHoursStore(JdbcClient db) {
    this.db = db;
  }

  record Schedule(String timeZone, int breakMinutes, int minimumNoticeHours, List<Rule> rules) {}

  record Rule(DayOfWeek weekday, LocalTime start, LocalTime end) {}

  boolean hasWeeklyHours(UUID specialistId) {
    return db.sql("SELECT count(*) FROM specialist_schedule WHERE specialist_id = :id")
            .param("id", specialistId)
            .query(Integer.class)
            .single()
        > 0;
  }

  Optional<Schedule> find(UUID specialistId) {
    return db.sql(
            """
            SELECT time_zone, break_minutes, minimum_notice_hours
              FROM specialist_schedule WHERE specialist_id = :id
            """)
        .param("id", specialistId)
        .query(
            (rs, n) ->
                new Schedule(
                    rs.getString("time_zone"),
                    rs.getInt("break_minutes"),
                    rs.getInt("minimum_notice_hours"),
                    rules(specialistId)))
        .optional();
  }

  /** Replaces the schedule and every rule; the caller holds the transaction. */
  void save(UUID specialistId, Schedule schedule, Instant now) {
    db.sql(
            """
            INSERT INTO specialist_schedule
                   (specialist_id, time_zone, break_minutes, minimum_notice_hours, saved_at)
            VALUES (:id, :tz, :b, :n, :now)
            ON CONFLICT (specialist_id) DO UPDATE
               SET time_zone = EXCLUDED.time_zone, break_minutes = EXCLUDED.break_minutes,
                   minimum_notice_hours = EXCLUDED.minimum_notice_hours, saved_at = EXCLUDED.saved_at
            """)
        .param("id", specialistId)
        .param("tz", schedule.timeZone())
        .param("b", schedule.breakMinutes())
        .param("n", schedule.minimumNoticeHours())
        .param("now", Timestamp.from(now))
        .update();
    db.sql("DELETE FROM availability_rule WHERE specialist_id = :id")
        .param("id", specialistId)
        .update();
    for (Rule rule : schedule.rules()) {
      db.sql(
              """
              INSERT INTO availability_rule (id, specialist_id, weekday, start_time, end_time)
              VALUES (:id, :s, :d, :start, :end)
              """)
          .param("id", UUID.randomUUID())
          .param("s", specialistId)
          .param("d", rule.weekday().getValue())
          .param("start", rule.start())
          .param("end", rule.end())
          .update();
    }
  }

  private List<Rule> rules(UUID specialistId) {
    return db.sql(
            """
            SELECT weekday, start_time, end_time FROM availability_rule
             WHERE specialist_id = :id ORDER BY weekday, start_time
            """)
        .param("id", specialistId)
        .query(
            (rs, n) ->
                new Rule(
                    DayOfWeek.of(rs.getInt("weekday")),
                    rs.getObject("start_time", LocalTime.class),
                    rs.getObject("end_time", LocalTime.class)))
        .list();
  }
}
