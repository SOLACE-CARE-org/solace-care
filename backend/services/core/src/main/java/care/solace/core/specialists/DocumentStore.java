package care.solace.core.specialists;

import java.sql.Timestamp;
import java.time.Instant;
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

  record StoredFile(
      UUID id,
      String kind,
      String objectKey,
      String fileName,
      String contentType,
      long size,
      Instant uploadedAt) {}

  List<StoredFile> list(UUID accountId) {
    return db.sql(
            """
            SELECT id, kind, object_key, file_name, content_type, size_bytes, uploaded_at
              FROM specialist_document WHERE account_id = :id ORDER BY kind, uploaded_at, id
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new StoredFile(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("object_key"),
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getTimestamp("uploaded_at").toInstant()))
        .list();
  }

  /** Replaces every file of one kind; returns the object keys that are no longer used. */
  List<String> replace(UUID accountId, String kind, List<StoredFile> files) {
    List<String> old =
        db.sql(
                "DELETE FROM specialist_document WHERE account_id = :id AND kind = :k"
                    + " RETURNING object_key")
            .param("id", accountId)
            .param("k", kind)
            .query(String.class)
            .list();
    for (StoredFile file : files) {
      db.sql(
              """
              INSERT INTO specialist_document
                     (id, account_id, kind, object_key, file_name, content_type, size_bytes,
                      uploaded_at)
              VALUES (:id, :a, :k, :o, :f, :c, :s, :u)
              """)
          .param("id", file.id())
          .param("a", accountId)
          .param("k", kind)
          .param("o", file.objectKey())
          .param("f", file.fileName())
          .param("c", file.contentType())
          .param("s", file.size())
          .param("u", Timestamp.from(file.uploadedAt()))
          .update();
    }
    return old;
  }
}
