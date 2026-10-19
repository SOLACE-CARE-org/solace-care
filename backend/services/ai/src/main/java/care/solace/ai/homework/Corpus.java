package care.solace.ai.homework;

import care.solace.ai.gateway.ModelGateway;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The companion's vetted corpus in pgvector (System Design, Retrieval; D57): bilingual passages
 * only, never anyone's words. With an embedding model the closest passages for the exercise come
 * first; without one, the exercise's passages are used as they are.
 */
@Component
class Corpus implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(Corpus.class);

  private final JdbcClient db;
  private final ModelGateway gateway;

  Corpus(JdbcClient db, ModelGateway gateway) {
    this.db = db;
    this.gateway = gateway;
  }

  /** Fills missing embeddings at start-up when an embedding model is configured. */
  @Override
  public void run(ApplicationArguments args) {
    try {
      for (var p :
          db.sql("SELECT id, text FROM companion_passage WHERE embedding IS NULL")
              .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
              .list()) {
        Optional<float[]> v = gateway.embed((String) p[1]);
        if (v.isEmpty()) {
          return;
        }
        db.sql("UPDATE companion_passage SET embedding = CAST(:v AS vector) WHERE id = :id")
            .param("v", literal(v.get()))
            .param("id", p[0])
            .update();
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not embed the companion corpus; words are used instead");
    }
  }

  /** Up to {@code k} passages for the exercise in the language, closest to the query first. */
  List<String> passages(String exercise, String locale, String query, int k) {
    Optional<float[]> v = query == null ? Optional.empty() : gateway.embed(query);
    if (v.isPresent()) {
      var found =
          db.sql(
                  """
                  SELECT text FROM companion_passage
                   WHERE exercise = :e AND locale = :l AND embedding IS NOT NULL
                   ORDER BY embedding <=> CAST(:v AS vector) LIMIT :k
                  """)
              .param("e", exercise)
              .param("l", locale)
              .param("v", literal(v.get()))
              .param("k", k)
              .query(String.class)
              .list();
      if (!found.isEmpty()) {
        return found;
      }
    }
    return db.sql(
            "SELECT text FROM companion_passage WHERE exercise = :e AND locale = :l"
                + " ORDER BY created_at LIMIT :k")
        .param("e", exercise)
        .param("l", locale)
        .param("k", k)
        .query(String.class)
        .list();
  }

  private static String literal(float[] v) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append(v[i]);
    }
    return b.append(']').toString();
  }
}
