package care.solace.core.bookings;

import care.solace.errors.SolaceException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code booking_dispute} (D41). */
@Repository
class DisputeStore {

  private final JdbcClient db;

  DisputeStore(JdbcClient db) {
    this.db = db;
  }

  record Dispute(
      UUID id,
      UUID bookingId,
      String note,
      String openedByName,
      Instant openedAt,
      String outcome,
      Long refundAmount,
      String reason,
      String resolvedByName,
      Instant resolvedAt) {

    boolean open() {
      return resolvedAt == null;
    }
  }

  void open(UUID bookingId, String note, String by, String byName, Instant now) {
    try {
      db.sql(
              """
              INSERT INTO booking_dispute (id, booking_id, note, opened_by, opened_by_name, opened_at)
              VALUES (:id, :b, :note, :by, :name, :now)
              """)
          .param("id", UUID.randomUUID())
          .param("b", bookingId)
          .param("note", note)
          .param("by", by)
          .param("name", byName)
          .param("now", Timestamp.from(now))
          .update();
    } catch (DuplicateKeyException e) {
      throw new SolaceException(BookingError.DISPUTE_OPEN);
    }
  }

  /** The open dispute, or else the most recent one. */
  Optional<Dispute> latest(UUID bookingId) {
    return db.sql(
            """
            SELECT * FROM booking_dispute WHERE booking_id = :b
             ORDER BY resolved_at IS NULL DESC, opened_at DESC LIMIT 1
            """)
        .param("b", bookingId)
        .query(DisputeStore::row)
        .optional();
  }

  Set<UUID> open(Collection<UUID> bookingIds) {
    if (bookingIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        db.sql(
                "SELECT booking_id FROM booking_dispute WHERE booking_id IN (:ids) AND resolved_at IS NULL")
            .param("ids", bookingIds)
            .query(UUID.class)
            .list());
  }

  Set<UUID> allOpen() {
    return new HashSet<>(
        db.sql("SELECT booking_id FROM booking_dispute WHERE resolved_at IS NULL")
            .query(UUID.class)
            .list());
  }

  void resolve(
      UUID bookingId,
      String outcome,
      Long refund,
      String reason,
      String by,
      String byName,
      Instant now) {
    db.sql(
            """
            UPDATE booking_dispute
               SET outcome = :o, refund_amount = :r, reason = :reason, resolved_by = :by,
                   resolved_by_name = :name, resolved_at = :now
             WHERE booking_id = :b AND resolved_at IS NULL
            """)
        .param("b", bookingId)
        .param("o", outcome)
        .param("r", refund)
        .param("reason", reason)
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Erasure keeps the dispute as the accounting record, without the note (D40). */
  void forgetNotes(Collection<UUID> bookingIds) {
    if (bookingIds.isEmpty()) {
      return;
    }
    db.sql("UPDATE booking_dispute SET note = '-', reason = NULL WHERE booking_id IN (:ids)")
        .param("ids", bookingIds)
        .update();
  }

  private static Dispute row(ResultSet rs, int n) throws SQLException {
    Timestamp resolved = rs.getTimestamp("resolved_at");
    return new Dispute(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getString("note"),
        rs.getString("opened_by_name"),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getString("outcome"),
        (Long) rs.getObject("refund_amount"),
        rs.getString("reason"),
        rs.getString("resolved_by_name"),
        resolved == null ? null : resolved.toInstant());
  }
}
