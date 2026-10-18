package care.solace.core.privacy;

import care.solace.core.identity.PersonalData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The consent history in the copy of the data. Erasure keeps it, pseudonymous, as the proof that
 * processing was lawful (D40).
 */
@Component
class ConsentData implements PersonalData {

  private final JdbcClient db;

  ConsentData(JdbcClient db) {
    this.db = db;
  }

  @Override
  public String section() {
    return "consents";
  }

  @Override
  public Object export(UUID accountId) {
    return db.sql("SELECT * FROM consent_record WHERE account_id = :a ORDER BY recorded_at, id")
        .param("a", accountId)
        .query(
            (rs, n) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("purpose", rs.getString("purpose"));
              m.put("granted", rs.getBoolean("granted"));
              m.put("version", rs.getString("version"));
              m.put("recordedAt", rs.getTimestamp("recorded_at").toInstant());
              return m;
            })
        .list();
  }

  @Override
  public void erase(UUID accountId) {
    // Kept on purpose: see the class comment.
  }
}
