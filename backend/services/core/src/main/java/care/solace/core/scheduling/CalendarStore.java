package care.solace.core.scheduling;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code calendar_entry}: bookings and time off. The exclusion constraint {@code
 * calendar_entry_no_overlap} refuses any overlap for one specialist.
 */
@Repository
class CalendarStore {

  private final JdbcClient db;

  CalendarStore(JdbcClient db) {
    this.db = db;
  }

  record Entry(UUID id, String kind, Instant start, Instant end, UUID bookingId, String reason) {}

  List<Entry> between(UUID specialistId, Instant from, Instant to) {
    return db.sql(
            """
            SELECT id, kind, lower(during) AS s, upper(during) AS e, booking_id, reason
              FROM calendar_entry
             WHERE specialist_id = :sid AND during && tstzrange(:from, :to)
             ORDER BY lower(during)
            """)
        .param("sid", specialistId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, n) ->
                new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getTimestamp("s").toInstant(),
                    rs.getTimestamp("e").toInstant(),
                    rs.getObject("booking_id", UUID.class),
                    rs.getString("reason")))
        .list();
  }

  /** Throws a DataIntegrityViolationException when it overlaps another entry. */
  void insertTimeOff(UUID id, UUID specialistId, Instant start, Instant end, String reason) {
    db.sql(
            """
            INSERT INTO calendar_entry (id, specialist_id, during, kind, reason)
            VALUES (:id, :sid, tstzrange(:s, :e), 'TIME_OFF', :r)
            """)
        .param("id", id)
        .param("sid", specialistId)
        .param("s", Timestamp.from(start))
        .param("e", Timestamp.from(end))
        .param("r", reason)
        .update();
  }

  /** Throws a DataIntegrityViolationException when it overlaps another entry. */
  void insertBooking(UUID bookingId, UUID specialistId, Instant start, Instant end) {
    db.sql(
            """
            INSERT INTO calendar_entry (id, specialist_id, during, kind, booking_id)
            VALUES (:id, :sid, tstzrange(:s, :e), 'BOOKING', :b)
            """)
        .param("id", UUID.randomUUID())
        .param("sid", specialistId)
        .param("s", Timestamp.from(start))
        .param("e", Timestamp.from(end))
        .param("b", bookingId)
        .update();
  }

  void deleteBooking(UUID bookingId) {
    db.sql("DELETE FROM calendar_entry WHERE booking_id = :b").param("b", bookingId).update();
  }

  void deleteTimeOff(UUID id, UUID specialistId) {
    db.sql(
            "DELETE FROM calendar_entry WHERE id = :id AND specialist_id = :sid AND kind = 'TIME_OFF'")
        .param("id", id)
        .param("sid", specialistId)
        .update();
  }
}
