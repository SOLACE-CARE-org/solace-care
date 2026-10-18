package care.solace.core.reviews;

import care.solace.core.identity.PersonalData;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A member's ratings in their copy of their data; erasure removes their words and keeps the stars,
 * which name no one (D40).
 */
@Component
class RatingData implements PersonalData {

  private final JdbcClient db;

  RatingData(JdbcClient db) {
    this.db = db;
  }

  @Override
  public String section() {
    return "ratings";
  }

  @Override
  public Object export(UUID accountId) {
    return db.sql(
            """
            SELECT booking_id, stars, tags, text, private_note, session_at, status FROM rating
             WHERE subject_id = :a AND NOT guest ORDER BY session_at
            """)
        .param("a", accountId)
        .query(
            (rs, n) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("bookingId", rs.getObject("booking_id", UUID.class));
              m.put("stars", rs.getInt("stars"));
              m.put("tags", Arrays.asList((String[]) rs.getArray("tags").getArray()));
              m.put("text", rs.getString("text"));
              m.put("privateNote", rs.getString("private_note"));
              m.put("sessionAt", rs.getTimestamp("session_at").toInstant());
              m.put("status", rs.getString("status"));
              return m;
            })
        .list();
  }

  @Override
  public void erase(UUID accountId) {
    db.sql(
            """
            UPDATE rating SET text = NULL, private_note = NULL, published_text = NULL,
                   reply = NULL, flags = '{}',
                   hidden_reason = CASE WHEN status IN ('PENDING', 'PUBLISHED')
                                        THEN 'ERASED' ELSE hidden_reason END,
                   status = CASE WHEN status IN ('PENDING', 'PUBLISHED') THEN 'HIDDEN' ELSE status END
             WHERE subject_id = :a AND NOT guest
            """)
        .param("a", accountId)
        .update();
  }
}
