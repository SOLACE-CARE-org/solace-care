package care.solace.core.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notification copy edited on the portal (D61), each change kept with its reason. */
@Repository
class CopyStore {

  /** Both languages as people wrote them. */
  record Edited(
      String key, String en, String fr, int version, String updatedByName, Instant updatedAt) {}

  record Version(int version, String en, String fr, String reason, String by, Instant at) {}

  private final JdbcClient db;

  CopyStore(JdbcClient db) {
    this.db = db;
  }

  Map<String, Edited> all() {
    return db
        .sql(
            "SELECT key, text_en, text_fr, version, updated_by_name, updated_at"
                + " FROM notification_copy")
        .query(
            (rs, n) ->
                new Edited(
                    rs.getString("key"),
                    rs.getString("text_en"),
                    rs.getString("text_fr"),
                    rs.getInt("version"),
                    rs.getString("updated_by_name"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list()
        .stream()
        .collect(Collectors.toMap(Edited::key, e -> e));
  }

  /**
   * Saves a new version over {@code from} (0 when the message was never edited); false when someone
   * changed it meanwhile. Call inside a transaction.
   */
  boolean save(
      String key,
      int from,
      String en,
      String fr,
      String reason,
      String by,
      String byName,
      Instant now) {
    int done =
        from == 0
            ? db.sql(
                    """
                    INSERT INTO notification_copy (key, text_en, text_fr, version, updated_by,
                                                   updated_by_name, updated_at)
                    VALUES (:k, :en, :fr, 1, :by, :name, :now)
                    ON CONFLICT (key) DO NOTHING
                    """)
                .param("k", key)
                .param("en", en)
                .param("fr", fr)
                .param("by", by)
                .param("name", byName)
                .param("now", Timestamp.from(now))
                .update()
            : db.sql(
                    """
                    UPDATE notification_copy
                       SET text_en = :en, text_fr = :fr, version = version + 1, updated_by = :by,
                           updated_by_name = :name, updated_at = :now
                     WHERE key = :k AND version = :v
                    """)
                .param("k", key)
                .param("en", en)
                .param("fr", fr)
                .param("v", from)
                .param("by", by)
                .param("name", byName)
                .param("now", Timestamp.from(now))
                .update();
    if (done != 1) {
      return false;
    }
    db.sql(
            """
            INSERT INTO notification_copy_version (key, version, text_en, text_fr, reason,
                                                   changed_by, changed_by_name, changed_at)
            VALUES (:k, :v, :en, :fr, :r, :by, :name, :now)
            """)
        .param("k", key)
        .param("v", from + 1)
        .param("en", en)
        .param("fr", fr)
        .param("r", reason)
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
    return true;
  }

  List<Version> versions(String key) {
    return db.sql(
            """
            SELECT version, text_en, text_fr, reason, changed_by_name, changed_at
              FROM notification_copy_version WHERE key = :k ORDER BY version DESC
            """)
        .param("k", key)
        .query(
            (rs, n) ->
                new Version(
                    rs.getInt("version"),
                    rs.getString("text_en"),
                    rs.getString("text_fr"),
                    rs.getString("reason"),
                    rs.getString("changed_by_name"),
                    rs.getTimestamp("changed_at").toInstant()))
        .list();
  }
}
