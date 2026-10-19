package care.solace.core.sessions;

import care.solace.core.identity.PersonalData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A specialist's prep notes (D56). The author exports their own; erasure of the author, or of the
 * seeker the booking was with, deletes them. Runs before the account itself is removed, while the
 * booking still names the seeker.
 */
@Component
@Order(0)
class PrepNoteData implements PersonalData {

  private final JdbcClient db;

  PrepNoteData(JdbcClient db) {
    this.db = db;
  }

  @Override
  public String section() {
    return "prepNotes";
  }

  @Override
  public Object export(UUID accountId) {
    var notes =
        db.sql(
                """
            SELECT booking_id, body, updated_at FROM session_prep_note
             WHERE specialist_id = :a ORDER BY updated_at
            """)
            .param("a", accountId)
            .query(
                (rs, n) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("bookingId", rs.getObject("booking_id", UUID.class));
                  m.put("body", rs.getString("body"));
                  m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                  return m;
                })
            .list();
    return notes.isEmpty() ? null : notes;
  }

  @Override
  public void erase(UUID accountId) {
    db.sql(
            """
            DELETE FROM session_prep_note
             WHERE specialist_id = :a
                OR booking_id IN (SELECT id FROM booking WHERE seeker_account_id = :a)
            """)
        .param("a", accountId)
        .update();
  }
}
