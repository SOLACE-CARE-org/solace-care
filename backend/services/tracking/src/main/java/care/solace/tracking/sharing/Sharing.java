package care.solace.tracking.sharing;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.PersonalData;
import care.solace.tracking.TrackingError;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sharing grants (Tracking Service Spec, Principles; D52): the person's call, forward-only,
 * revocable at any time, and notes only with a separate yes.
 */
@Service
public class Sharing implements PersonalData {

  public record Grant(
      UUID id, UUID specialistId, boolean includesNotes, Instant grantedAt, Instant revokedAt) {}

  private final JdbcClient db;
  private final LiveBookings bookings;
  private final Clock clock;

  Sharing(JdbcClient db, LiveBookings bookings, Clock clock) {
    this.db = db;
    this.bookings = bookings;
    this.clock = clock;
  }

  /**
   * A grant to a specialist the person has a live booking with. A new grant replaces the active
   * one, and the forward-only window restarts from now.
   */
  @Transactional
  public Grant grant(UUID user, UUID specialist, boolean includesNotes) {
    if (!bookings.live(user, specialist)) {
      throw new SolaceException(TrackingError.NO_ACCESS);
    }
    Instant now = clock.instant();
    db.sql(
            """
            UPDATE share_grant SET revoked_at = :now
             WHERE user_id = :u AND specialist_id = :s AND revoked_at IS NULL
            """)
        .param("u", user)
        .param("s", specialist)
        .param("now", Timestamp.from(now))
        .update();
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO share_grant (id, user_id, specialist_id, includes_notes, granted_at)
              VALUES (:id, :u, :s, :n, :now)
              """)
          .param("id", id)
          .param("u", user)
          .param("s", specialist)
          .param("n", includesNotes)
          .param("now", Timestamp.from(now))
          .update();
    } catch (DuplicateKeyException e) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    return new Grant(id, specialist, includesNotes, now, null);
  }

  public List<Grant> grants(UUID user) {
    return db.sql("SELECT * FROM share_grant WHERE user_id = :u ORDER BY granted_at DESC")
        .param("u", user)
        .query(Sharing::row)
        .list();
  }

  public void revoke(UUID user, UUID grantId) {
    int n =
        db.sql(
                """
                UPDATE share_grant SET revoked_at = :now
                 WHERE id = :id AND user_id = :u AND revoked_at IS NULL
                """)
            .param("id", grantId)
            .param("u", user)
            .param("now", Timestamp.from(clock.instant()))
            .update();
    if (n == 0) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  /** The active grant from this person to this specialist, if any. */
  public Optional<Grant> active(UUID user, UUID specialist) {
    return db.sql(
            """
            SELECT * FROM share_grant
             WHERE user_id = :u AND specialist_id = :s AND revoked_at IS NULL
            """)
        .param("u", user)
        .param("s", specialist)
        .query(Sharing::row)
        .optional();
  }

  private static Grant row(ResultSet rs, int n) throws SQLException {
    Timestamp revoked = rs.getTimestamp("revoked_at");
    return new Grant(
        rs.getObject("id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getBoolean("includes_notes"),
        rs.getTimestamp("granted_at").toInstant(),
        revoked == null ? null : revoked.toInstant());
  }

  @Override
  public void erase(UUID userId) {
    db.sql("DELETE FROM share_grant WHERE user_id = :u").param("u", userId).update();
  }
}
