package care.solace.core.messaging;

import care.solace.core.identity.PersonalData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The messages a member was sent, and their devices and settings; erasure deletes all of it. */
@Component
class MessageData implements PersonalData {

  private final JdbcClient db;

  MessageData(JdbcClient db) {
    this.db = db;
  }

  @Override
  public String section() {
    return "messages";
  }

  @Override
  public Object export(UUID accountId) {
    return db.sql(
            """
            SELECT kind, channel, text, created_at FROM notification
             WHERE account_id = :a ORDER BY created_at
            """)
        .param("a", accountId)
        .query(
            (rs, n) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("kind", rs.getString("kind"));
              m.put("channel", rs.getString("channel"));
              m.put("text", rs.getString("text"));
              m.put("at", rs.getTimestamp("created_at").toInstant());
              return m;
            })
        .list();
  }

  @Override
  public void erase(UUID accountId) {
    for (String table : new String[] {"notification", "push_device", "notification_setting"}) {
      db.sql("DELETE FROM " + table + " WHERE account_id = :a").param("a", accountId).update();
    }
  }
}
