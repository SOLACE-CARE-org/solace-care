package care.solace.core.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SMS and email senders (D61), each change kept as a version with its reason. */
@Repository
class SenderStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  enum Channel {
    SMS,
    EMAIL
  }

  enum Adapter {
    HTTP_SMS,
    SMTP,
    LOGGING
  }

  enum Status {
    DRAFT,
    ACTIVE,
    DISABLED
  }

  record Sender(
      String code,
      Channel channel,
      Adapter adapter,
      String displayName,
      Status status,
      int priority,
      String secretRef,
      Map<String, Object> settings,
      int version,
      String updatedByName,
      Instant updatedAt) {}

  record Version(int version, Map<String, Object> snapshot, String reason, String by, Instant at) {}

  /** Who changed it, as audit and the version table name them. */
  record By(String id, String name) {}

  private final JdbcClient db;

  SenderStore(JdbcClient db) {
    this.db = db;
  }

  List<Sender> all() {
    return db.sql("SELECT * FROM message_sender ORDER BY channel, priority, code")
        .query(SenderStore::sender)
        .list();
  }

  Optional<Sender> find(String code) {
    return db.sql("SELECT * FROM message_sender WHERE code = :c")
        .param("c", code)
        .query(SenderStore::sender)
        .optional();
  }

  void insert(Sender s, String reason, By by, Instant now) {
    db.sql(
            """
            INSERT INTO message_sender (code, channel, adapter, display_name, status, priority,
                                        secret_ref, settings, version, updated_by, updated_by_name,
                                        updated_at)
            VALUES (:c, :ch, :a, :n, :s, :p, :r, CAST(:j AS jsonb), 1, :by, :name, :now)
            """)
        .param("c", s.code())
        .param("ch", s.channel().name())
        .param("a", s.adapter().name())
        .param("n", s.displayName())
        .param("s", s.status().name())
        .param("p", s.priority())
        .param("r", s.secretRef())
        .param("j", write(s.settings()))
        .param("by", by.id())
        .param("name", by.name())
        .param("now", Timestamp.from(now))
        .update();
    version(s, 1, reason, by, now);
  }

  /** Writes {@code next} over version {@code from}; false when someone changed it meanwhile. */
  boolean update(Sender next, int from, String reason, By by, Instant now) {
    boolean done =
        db.sql(
                    """
                    UPDATE message_sender
                       SET display_name = :n, status = :s, priority = :p, secret_ref = :r,
                           settings = CAST(:j AS jsonb), version = version + 1, updated_by = :by,
                           updated_by_name = :name, updated_at = :now
                     WHERE code = :c AND version = :v
                    """)
                .param("c", next.code())
                .param("n", next.displayName())
                .param("s", next.status().name())
                .param("p", next.priority())
                .param("r", next.secretRef())
                .param("j", write(next.settings()))
                .param("v", from)
                .param("by", by.id())
                .param("name", by.name())
                .param("now", Timestamp.from(now))
                .update()
            == 1;
    if (done) {
      version(next, from + 1, reason, by, now);
    }
    return done;
  }

  List<Version> versions(String code) {
    return db.sql(
            """
            SELECT version, snapshot, reason, changed_by_name, changed_at
              FROM message_sender_version WHERE code = :c ORDER BY version DESC
            """)
        .param("c", code)
        .query(
            (rs, n) ->
                new Version(
                    rs.getInt("version"),
                    read(rs.getString("snapshot")),
                    rs.getString("reason"),
                    rs.getString("changed_by_name"),
                    rs.getTimestamp("changed_at").toInstant()))
        .list();
  }

  private void version(Sender s, int version, String reason, By by, Instant now) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("channel", s.channel().name());
    snapshot.put("adapter", s.adapter().name());
    snapshot.put("displayName", s.displayName());
    snapshot.put("status", s.status().name());
    snapshot.put("priority", s.priority());
    snapshot.put("secretRef", s.secretRef());
    snapshot.put("settings", s.settings());
    db.sql(
            """
            INSERT INTO message_sender_version (code, version, snapshot, reason, changed_by,
                                                changed_by_name, changed_at)
            VALUES (:c, :v, CAST(:j AS jsonb), :r, :by, :name, :now)
            """)
        .param("c", s.code())
        .param("v", version)
        .param("j", write(snapshot))
        .param("r", reason)
        .param("by", by.id())
        .param("name", by.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Sender sender(ResultSet rs, int n) throws SQLException {
    return new Sender(
        rs.getString("code"),
        Channel.valueOf(rs.getString("channel")),
        Adapter.valueOf(rs.getString("adapter")),
        rs.getString("display_name"),
        Status.valueOf(rs.getString("status")),
        rs.getInt("priority"),
        rs.getString("secret_ref"),
        read(rs.getString("settings")),
        rs.getInt("version"),
        rs.getString("updated_by_name"),
        rs.getTimestamp("updated_at").toInstant());
  }

  static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> read(String json) {
    try {
      return json == null ? Map.of() : JSON.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
