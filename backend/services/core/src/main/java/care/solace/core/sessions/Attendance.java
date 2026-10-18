package care.solace.core.sessions;

import care.solace.core.bookings.BookingAttendance;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Attendance and flags for the booking screens of the admin portal (D36, D41). */
@Component
class Attendance implements BookingAttendance {

  private final JdbcClient db;

  Attendance(JdbcClient db) {
    this.db = db;
  }

  @Override
  public List<Event> events(UUID bookingId) {
    return db.sql("SELECT at, role, kind FROM session_event WHERE booking_id = :b ORDER BY at, id")
        .param("b", bookingId)
        .query(
            (rs, n) ->
                new Event(
                    rs.getTimestamp("at").toInstant(), rs.getString("role"), rs.getString("kind")))
        .list();
  }

  @Override
  public Optional<Instant> openFlag(UUID bookingId) {
    return db.sql(
            "SELECT created_at FROM session_flag WHERE booking_id = :b AND resolved_at IS NULL")
        .param("b", bookingId)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }

  @Override
  public Set<UUID> flagged(Collection<UUID> bookingIds) {
    if (bookingIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        db.sql(
                "SELECT booking_id FROM session_flag WHERE booking_id IN (:ids) AND resolved_at IS NULL")
            .param("ids", bookingIds)
            .query(UUID.class)
            .list());
  }

  @Override
  public Set<UUID> allFlagged() {
    return new HashSet<>(
        db.sql("SELECT booking_id FROM session_flag WHERE resolved_at IS NULL")
            .query(UUID.class)
            .list());
  }

  /** A role is waiting when it joined last and has not left, and the other is not in the room. */
  @Override
  public Map<UUID, String> waiting(Collection<UUID> bookingIds) {
    Map<UUID, String> waiting = new HashMap<>();
    if (bookingIds.isEmpty()) {
      return waiting;
    }
    Map<UUID, Map<String, Boolean>> inRoom = new HashMap<>();
    db.sql(
            """
            SELECT booking_id, role, kind FROM session_event
             WHERE booking_id IN (:ids) AND role IS NOT NULL ORDER BY at, id
            """)
        .param("ids", bookingIds)
        .query(
            rs -> {
              inRoom
                  .computeIfAbsent(rs.getObject("booking_id", UUID.class), k -> new HashMap<>())
                  .put(rs.getString("role"), rs.getString("kind").equals("JOINED"));
            });
    inRoom.forEach(
        (booking, roles) -> {
          var present = roles.entrySet().stream().filter(Map.Entry::getValue).toList();
          if (present.size() == 1) {
            waiting.put(booking, present.get(0).getKey());
          }
        });
    return waiting;
  }

  @Override
  public void resolveFlag(UUID bookingId, String outcome, Instant now) {
    db.sql(
            """
            UPDATE session_flag SET resolved_at = :now, outcome = :o
             WHERE booking_id = :b AND resolved_at IS NULL
            """)
        .param("b", bookingId)
        .param("o", outcome)
        .param("now", Timestamp.from(now))
        .update();
  }
}
