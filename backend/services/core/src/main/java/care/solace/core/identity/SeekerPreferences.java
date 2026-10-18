package care.solace.core.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A seeker's chosen session languages, for matching (tracking invites, the health check). */
@Component
public class SeekerPreferences {

  private final JdbcClient db;

  SeekerPreferences(JdbcClient db) {
    this.db = db;
  }

  public List<String> sessionLanguages(UUID accountId) {
    return db.sql(
            "SELECT language_code FROM seeker_session_language WHERE account_id = :a ORDER BY language_code")
        .param("a", accountId)
        .query(String.class)
        .list();
  }
}
