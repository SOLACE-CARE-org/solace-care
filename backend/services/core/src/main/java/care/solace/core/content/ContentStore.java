package care.solace.core.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code content_item} and {@code content_version} (D44). */
@Repository
class ContentStore {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<Dial>> NUMBERS = new TypeReference<>() {};

  record Dial(String service, String number) {}

  record Text(String titleEn, String bodyEn, String titleFr, String bodyFr, List<Dial> numbers) {}

  record Item(
      UUID id,
      String slug,
      String type,
      String country,
      Text draft,
      boolean enDone,
      boolean frDone,
      String clinical,
      String clinicalByName,
      String editedBy,
      String editedByName,
      boolean edited,
      int version,
      Instant updatedAt) {}

  record Version(int version, Text text, Instant publishedAt) {}

  private final JdbcClient db;

  ContentStore(JdbcClient db) {
    this.db = db;
  }

  List<Item> all(String type) {
    return db.sql(
            "SELECT * FROM content_item"
                + (type == null ? "" : " WHERE type = :t")
                + " ORDER BY type, slug")
        .params(type == null ? java.util.Map.of() : java.util.Map.of("t", type))
        .query(ContentStore::item)
        .list();
  }

  Optional<Item> find(UUID id) {
    return db.sql("SELECT * FROM content_item WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(ContentStore::item)
        .optional();
  }

  Optional<Item> bySlug(String slug) {
    return db.sql("SELECT * FROM content_item WHERE slug = :s")
        .param("s", slug)
        .query(ContentStore::item)
        .optional();
  }

  boolean create(UUID id, String slug, String type, String country, String clinical, Instant now) {
    return db.sql(
                """
                INSERT INTO content_item (id, slug, type, country, clinical, updated_at)
                VALUES (:id, :s, :t, :c, :cl, :now) ON CONFLICT (slug) DO NOTHING
                """)
            .param("id", id)
            .param("s", slug)
            .param("t", type)
            .param("c", country)
            .param("cl", clinical)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void saveDraft(
      UUID id,
      Text text,
      boolean enDone,
      boolean frDone,
      String clinical,
      String by,
      String byName,
      Instant now) {
    db.sql(
            """
            UPDATE content_item
               SET title_en = :te, body_en = :be, title_fr = :tf, body_fr = :bf,
                   numbers = CAST(:n AS jsonb), en_done = :ed, fr_done = :fd, clinical = :cl,
                   clinical_by = CASE WHEN :cl = 'APPROVED' THEN clinical_by END,
                   clinical_by_name = CASE WHEN :cl = 'APPROVED' THEN clinical_by_name END,
                   edited_by = :by, edited_by_name = :name, edited = TRUE, updated_at = :now
             WHERE id = :id
            """)
        .param("id", id)
        .param("te", text.titleEn())
        .param("be", text.bodyEn())
        .param("tf", text.titleFr())
        .param("bf", text.bodyFr())
        .param("n", json(text.numbers()))
        .param("ed", enDone)
        .param("fd", frDone)
        .param("cl", clinical)
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  void markDone(UUID id, String language, boolean done, Instant now) {
    db.sql(
            "UPDATE content_item SET "
                + (language.equals("fr") ? "fr_done" : "en_done")
                + " = :d, updated_at = :now WHERE id = :id")
        .param("id", id)
        .param("d", done)
        .param("now", Timestamp.from(now))
        .update();
  }

  void clinical(UUID id, String state, String by, String byName, Instant now) {
    db.sql(
            """
            UPDATE content_item SET clinical = :s, clinical_by = :by, clinical_by_name = :name,
                   updated_at = :now WHERE id = :id
            """)
        .param("id", id)
        .param("s", state)
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  int publish(Item item, String reason, String byName, Instant now) {
    int version = item.version() + 1;
    db.sql(
            """
            INSERT INTO content_version (item_id, version, title_en, body_en, title_fr, body_fr,
                                         numbers, reason, published_by_name, published_at)
            VALUES (:id, :v, :te, :be, :tf, :bf, CAST(:n AS jsonb), :r, :by, :now)
            """)
        .param("id", item.id())
        .param("v", version)
        .param("te", item.draft().titleEn())
        .param("be", item.draft().bodyEn())
        .param("tf", item.draft().titleFr())
        .param("bf", item.draft().bodyFr())
        .param("n", json(item.draft().numbers()))
        .param("r", reason)
        .param("by", byName)
        .param("now", Timestamp.from(now))
        .update();
    db.sql("UPDATE content_item SET version = :v, edited = FALSE, updated_at = :now WHERE id = :id")
        .param("id", item.id())
        .param("v", version)
        .param("now", Timestamp.from(now))
        .update();
    return version;
  }

  Optional<Version> latest(UUID itemId) {
    return db.sql("SELECT * FROM content_version WHERE item_id = :id ORDER BY version DESC LIMIT 1")
        .param("id", itemId)
        .query(
            (rs, n) ->
                new Version(
                    rs.getInt("version"), text(rs), rs.getTimestamp("published_at").toInstant()))
        .optional();
  }

  private static Item item(ResultSet rs, int n) throws SQLException {
    return new Item(
        rs.getObject("id", UUID.class),
        rs.getString("slug"),
        rs.getString("type"),
        rs.getString("country"),
        text(rs),
        rs.getBoolean("en_done"),
        rs.getBoolean("fr_done"),
        rs.getString("clinical"),
        rs.getString("clinical_by_name"),
        rs.getString("edited_by"),
        rs.getString("edited_by_name"),
        rs.getBoolean("edited"),
        rs.getInt("version"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Text text(ResultSet rs) throws SQLException {
    List<Dial> numbers;
    try {
      numbers = JSON.readValue(rs.getString("numbers"), NUMBERS);
    } catch (JsonProcessingException e) {
      numbers = List.of();
    }
    return new Text(
        rs.getString("title_en"),
        rs.getString("body_en"),
        rs.getString("title_fr"),
        rs.getString("body_fr"),
        numbers);
  }

  private static String json(List<Dial> numbers) {
    try {
      return JSON.writeValueAsString(numbers == null ? List.of() : numbers);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
