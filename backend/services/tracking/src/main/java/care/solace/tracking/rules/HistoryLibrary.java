package care.solace.tracking.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code synthetic_history}: the made-up histories rules are tested against (D61). Seeded ones come
 * first; staff add more and never edit them, so a past test run stays explainable.
 */
@Repository
public class HistoryLibrary {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param labels {"en": ..., "fr": ...}
   * @param context context question answers by code
   */
  public record Stored(
      String code,
      JsonNode labels,
      JsonNode recipe,
      JsonNode context,
      boolean seeded,
      String createdByName,
      Instant createdAt) {}

  private final JdbcClient db;

  HistoryLibrary(JdbcClient db) {
    this.db = db;
  }

  public List<Stored> all() {
    return db.sql("SELECT * FROM synthetic_history ORDER BY seeded DESC, created_at, code")
        .query(HistoryLibrary::row)
        .list();
  }

  public boolean exists(String code) {
    return db.sql("SELECT count(*) FROM synthetic_history WHERE code = :c")
            .param("c", code)
            .query(Integer.class)
            .single()
        > 0;
  }

  public void add(
      String code,
      JsonNode labels,
      JsonNode recipe,
      JsonNode context,
      String by,
      String byName,
      Instant now) {
    db.sql(
            """
            INSERT INTO synthetic_history (id, code, labels_json, recipe_json, context_json, seeded,
                   created_by, created_by_name, created_at)
            VALUES (:id, :code, CAST(:labels AS jsonb), CAST(:recipe AS jsonb),
                    CAST(:context AS jsonb), FALSE, :by, :name, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("code", code)
        .param("labels", json(labels))
        .param("recipe", json(recipe))
        .param("context", json(context))
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Stored row(ResultSet rs, int n) throws SQLException {
    return new Stored(
        rs.getString("code"),
        tree(rs.getString("labels_json")),
        tree(rs.getString("recipe_json")),
        tree(rs.getString("context_json")),
        rs.getBoolean("seeded"),
        rs.getString("created_by_name"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static String json(JsonNode node) {
    try {
      return JSON.writeValueAsString(node == null ? JSON.createObjectNode() : node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode tree(String json) throws SQLException {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new SQLException(e);
    }
  }
}
