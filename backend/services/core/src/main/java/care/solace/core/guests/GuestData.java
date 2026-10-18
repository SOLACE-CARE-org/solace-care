package care.solace.core.guests;

import care.solace.core.identity.PersonalData;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Guest contacts a member claimed (D34): erasure removes their numbers and booking links (D40). The
 * bookings themselves are in the bookings section.
 */
@Component
class GuestData implements PersonalData {

  private final JdbcClient db;

  GuestData(JdbcClient db) {
    this.db = db;
  }

  @Override
  public String section() {
    return "guestContacts";
  }

  @Override
  public Object export(UUID accountId) {
    return db.sql(
            "SELECT phone FROM guest_contact WHERE claimed_by_account_id = :a AND phone IS NOT NULL")
        .param("a", accountId)
        .query(String.class)
        .list();
  }

  @Override
  public void erase(UUID accountId) {
    db.sql(
            """
            DELETE FROM guest_token WHERE guest_contact_id IN
              (SELECT id FROM guest_contact WHERE claimed_by_account_id = :a)
            """)
        .param("a", accountId)
        .update();
    db.sql(
            """
            UPDATE guest_contact SET phone = NULL, purged_at = now()
             WHERE claimed_by_account_id = :a
            """)
        .param("a", accountId)
        .update();
  }
}
