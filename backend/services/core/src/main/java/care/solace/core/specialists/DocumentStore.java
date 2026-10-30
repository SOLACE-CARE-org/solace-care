package care.solace.core.specialists;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code specialist_document}: what each specialist uploaded; the bytes are in object storage. */
@Repository
class DocumentStore {

  private final JdbcClient db;

  DocumentStore(JdbcClient db) {
    this.db = db;
  }

  /**
   * @param profession the profession a LICENCE is for (D62); null for every other kind
   */
  record StoredFile(
      UUID id,
      String kind,
      String profession,
      String objectKey,
      String fileName,
      String contentType,
      long size,
      Instant uploadedAt) {}

  List<StoredFile> list(UUID accountId) {
    return db.sql(
            """
            SELECT id, kind, profession_code, object_key, file_name, content_type, size_bytes,
                   uploaded_at
              FROM specialist_document WHERE account_id = :id
             ORDER BY kind, profession_code NULLS FIRST, uploaded_at, id
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new StoredFile(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("profession_code"),
                    rs.getString("object_key"),
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getTimestamp("uploaded_at").toInstant()))
        .list();
  }

  /**
   * Replaces every file of one kind, or of one kind and profession; returns the object keys that
   * are no longer used.
   */
  List<String> replace(UUID accountId, String kind, String profession, List<StoredFile> files) {
    List<String> old =
        db.sql(
                "DELETE FROM specialist_document WHERE account_id = :id AND kind = :k"
                    + " AND profession_code IS NOT DISTINCT FROM :p RETURNING object_key")
            .param("id", accountId)
            .param("k", kind)
            .param("p", profession)
            .query(String.class)
            .list();
    for (StoredFile file : files) {
      insert(accountId, file);
    }
    return old;
  }

  void insert(UUID accountId, StoredFile file) {
    db.sql(
            """
            INSERT INTO specialist_document
                   (id, account_id, kind, profession_code, object_key, file_name, content_type,
                    size_bytes, uploaded_at)
            VALUES (:id, :a, :k, :p, :o, :f, :c, :s, :u)
            """)
        .param("id", file.id())
        .param("a", accountId)
        .param("k", file.kind())
        .param("p", file.profession())
        .param("o", file.objectKey())
        .param("f", file.fileName())
        .param("c", file.contentType())
        .param("s", file.size())
        .param("u", Timestamp.from(file.uploadedAt()))
        .update();
  }

  /** Licences of professions a draft no longer lists; returns the object keys removed. */
  List<String> removeLicencesExcept(UUID accountId, Collection<String> professions) {
    return db.sql(
            "DELETE FROM specialist_document WHERE account_id = :id AND kind = 'LICENCE'"
                + " AND profession_code NOT IN (:p) RETURNING object_key")
        .param("id", accountId)
        .param("p", professions)
        .query(String.class)
        .list();
  }

  /** One file, if its row is still there; returns its object key. */
  List<String> remove(UUID accountId, UUID id) {
    return db.sql(
            "DELETE FROM specialist_document WHERE account_id = :a AND id = :id"
                + " RETURNING object_key")
        .param("a", accountId)
        .param("id", id)
        .query(String.class)
        .list();
  }
}
