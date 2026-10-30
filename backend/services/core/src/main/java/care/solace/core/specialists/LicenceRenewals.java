package care.solace.core.specialists;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Approved specialists with a licence that ends within 60 days, for any of their approved
 * professions (D62), who have not been reminded for that date yet (notification-copy.md, Licence
 * renewal). 60 days is the copy's own number.
 */
@Component
public class LicenceRenewals {

  public static final int DAYS_BEFORE = 60;

  public record Due(UUID specialistId, LocalDate validUntil) {}

  private final JdbcClient db;

  LicenceRenewals(JdbcClient db) {
    this.db = db;
  }

  public List<Due> due(LocalDate today) {
    return db.sql(
            """
            SELECT DISTINCT p.account_id, sp.licence_valid_until
              FROM specialist_profile p
              JOIN specialist_profession sp ON sp.account_id = p.account_id
             WHERE p.verification_status = 'APPROVED'
               AND sp.status = 'APPROVED'
               AND sp.licence_valid_until IS NOT NULL
               AND sp.licence_valid_until >= :today
               AND sp.licence_valid_until <= :until
               AND NOT EXISTS (SELECT 1 FROM licence_reminder r
                                WHERE r.specialist_id = p.account_id
                                  AND r.valid_until = sp.licence_valid_until)
            """)
        .param("today", today)
        .param("until", today.plusDays(DAYS_BEFORE))
        .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class)))
        .list();
  }

  /** True the first time only. */
  public boolean markSent(Due due, Instant now) {
    return db.sql(
                """
                INSERT INTO licence_reminder (specialist_id, valid_until, sent_at)
                VALUES (:s, :v, :now) ON CONFLICT DO NOTHING
                """)
            .param("s", due.specialistId())
            .param("v", due.validUntil())
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }
}
