package care.solace.ai.homework;

import care.solace.ai.scribe.ScribeError;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Homework a specialist sets after a session (AssignHomework.dc.html; System Design, AI feature 5;
 * D57): a thought record, a sleep diary, worry time or their own task, a number of times or daily
 * until the next session. Only for a registered seeker they had a session with. The seeker's
 * answers are theirs; the specialist sees how many are done.
 */
@Service
public class Homeworks {

  static final Set<String> EXERCISES =
      Set.of("THOUGHT_RECORD", "SLEEP_DIARY", "WORRY_TIME", "CUSTOM");
  static final Set<String> FREQUENCIES = Set.of("TIMES", "DAILY", "ONCE");

  public record Assignment(
      String exercise,
      String customTask,
      String frequency,
      Integer times,
      String note,
      boolean companionGuided,
      Instant dueBy) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Homework(
      UUID id,
      UUID bookingId,
      UUID specialistId,
      String exercise,
      String customTask,
      String frequency,
      int target,
      int done,
      String note,
      boolean companionGuided,
      Instant dueBy,
      Instant createdAt) {}

  record Owned(Homework homework, UUID seekerSubjectId) {}

  private final JdbcClient db;
  private final Duration defaultDue;
  private final Clock clock;

  Homeworks(
      JdbcClient db,
      @Value("${solace.ai.homework.default-due:P7D}") Duration defaultDue,
      Clock clock) {
    this.db = db;
    this.defaultDue = defaultDue;
    this.clock = clock;
  }

  public Homework assign(UUID bookingId, UUID specialist, Assignment a) {
    if (!EXERCISES.contains(a.exercise())
        || !FREQUENCIES.contains(a.frequency())
        || ("CUSTOM".equals(a.exercise())
            && (a.customTask() == null
                || a.customTask().isBlank()
                || a.customTask().length() > 300))
        || ("TIMES".equals(a.frequency()) && (a.times() == null || a.times() < 1 || a.times() > 14))
        || (a.note() != null && a.note().length() > 500)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    UUID seeker =
        db.sql(
                """
                SELECT seeker_subject_id FROM ended_session
                 WHERE booking_id = :b AND specialist_id = :sp AND NOT guest
                """)
            .param("b", bookingId)
            .param("sp", specialist)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new SolaceException(ScribeError.NO_SESSION));
    Instant now = clock.instant();
    Instant due = a.dueBy() == null ? now.plus(defaultDue) : a.dueBy();
    if (!due.isAfter(now) || due.isAfter(now.plus(Duration.ofDays(60)))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    int target =
        switch (a.frequency()) {
          case "TIMES" -> a.times();
          case "ONCE" -> 1;
          default -> (int) Math.max(1, Math.min(60, Duration.between(now, due).toDays()));
        };
    UUID id = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO homework (id, booking_id, specialist_id, seeker_subject_id, exercise,
                                  custom_task, frequency, target, note, companion_guided, due_by,
                                  created_at)
            VALUES (:id, :b, :sp, :se, :ex, :ct, :f, :t, :n, :cg, :due, :now)
            """)
        .param("id", id)
        .param("b", bookingId)
        .param("sp", specialist)
        .param("se", seeker)
        .param("ex", a.exercise())
        .param("ct", "CUSTOM".equals(a.exercise()) ? a.customTask().strip() : null)
        .param("f", a.frequency())
        .param("t", target)
        .param("n", a.note() == null || a.note().isBlank() ? null : a.note().strip())
        .param("cg", a.companionGuided())
        .param("due", Timestamp.from(due))
        .param("now", Timestamp.from(now))
        .update();
    return find(id).orElseThrow().homework();
  }

  public List<Homework> forBooking(UUID bookingId, UUID specialist) {
    return db
        .sql(SELECT + " WHERE h.booking_id = :b AND h.specialist_id = :sp ORDER BY h.created_at")
        .param("b", bookingId)
        .param("sp", specialist)
        .query(Homeworks::row)
        .list()
        .stream()
        .map(Owned::homework)
        .toList();
  }

  /** The seeker's homework that is open or ended in the last [14] days, soonest due first. */
  public List<Homework> mine(UUID seeker) {
    return db
        .sql(SELECT + " WHERE h.seeker_subject_id = :s AND h.due_by > :since ORDER BY h.due_by")
        .param("s", seeker)
        .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(14))))
        .query(Homeworks::row)
        .list()
        .stream()
        .map(Owned::homework)
        .toList();
  }

  Optional<Owned> find(UUID id) {
    return db.sql(SELECT + " WHERE h.id = :id").param("id", id).query(Homeworks::row).optional();
  }

  /** Erasure of either person (D40). */
  public void erase(UUID subject) {
    db.sql("DELETE FROM homework WHERE seeker_subject_id = :s OR specialist_id = :s")
        .param("s", subject)
        .update();
    db.sql("DELETE FROM ended_session WHERE seeker_subject_id = :s OR specialist_id = :s")
        .param("s", subject)
        .update();
  }

  private static final String SELECT =
      """
      SELECT h.*, (SELECT count(*) FROM homework_entry e
                    WHERE e.homework_id = h.id AND e.completed) AS done
        FROM homework h
      """;

  private static Owned row(ResultSet rs, int n) throws SQLException {
    return new Owned(
        new Homework(
            rs.getObject("id", UUID.class),
            rs.getObject("booking_id", UUID.class),
            rs.getObject("specialist_id", UUID.class),
            rs.getString("exercise"),
            rs.getString("custom_task"),
            rs.getString("frequency"),
            rs.getInt("target"),
            rs.getInt("done"),
            rs.getString("note"),
            rs.getBoolean("companion_guided"),
            rs.getTimestamp("due_by").toInstant(),
            rs.getTimestamp("created_at").toInstant()),
        rs.getObject("seeker_subject_id", UUID.class));
  }
}
