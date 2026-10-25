package care.solace.ai.homework;

import care.solace.lists.ReviewedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The companion's vetted corpus as a reviewed list (D61): bilingual passages per exercise, changed
 * by a clinician and signed off by another. A signed-off version replaces the passages, and a
 * changed passage is embedded again. The passages the migration added are version 0.
 */
@Component
class CorpusList implements ReviewedList {

  private final JdbcClient db;
  private final ObjectMapper json;
  private final Corpus corpus;
  private final TransactionTemplate transactions;

  CorpusList(JdbcClient db, ObjectMapper json, Corpus corpus, TransactionTemplate transactions) {
    this.db = db;
    this.json = json;
    this.corpus = corpus;
    this.transactions = transactions;
  }

  @Override
  public String code() {
    return "ai.companion.corpus";
  }

  @Override
  public String description() {
    return "The companion's vetted passages, per exercise, in EN and FR";
  }

  @Override
  public JsonNode defaults() {
    ArrayNode out = json.createArrayNode();
    db.sql(
            "SELECT id, exercise, locale, text, source FROM companion_passage ORDER BY exercise, locale")
        .query(
            rs -> {
              out.addObject()
                  .put("id", rs.getString("id"))
                  .put("exercise", rs.getString("exercise"))
                  .put("locale", rs.getString("locale"))
                  .put("text", rs.getString("text"))
                  .put("source", rs.getString("source"));
            });
    return out;
  }

  /** Each passage once, every exercise in both languages. */
  @Override
  public void validate(JsonNode entries) {
    if (!entries.isArray() || entries.isEmpty()) {
      throw new IllegalArgumentException("a list of passages");
    }
    Set<String> ids = new HashSet<>();
    Map<String, Set<String>> languages = new HashMap<>();
    for (JsonNode p : entries) {
      String id = p.path("id").asText();
      String exercise = p.path("exercise").asText();
      String locale = p.path("locale").asText();
      String text = p.path("text").asText();
      try {
        UUID.fromString(id);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("each passage needs a uuid id");
      }
      if (!ids.add(id)
          || !exercise.matches("^[A-Z][A-Z_]{1,39}$")
          || !("en".equals(locale) || "fr".equals(locale))
          || text.isBlank()
          || text.length() > 1000
          || p.path("source").asText().isBlank()) {
        throw new IllegalArgumentException(
            "each passage needs an exercise, en or fr, text and a source");
      }
      languages.computeIfAbsent(exercise, e -> new HashSet<>()).add(locale);
    }
    if (languages.values().stream().anyMatch(l -> l.size() < 2)) {
      throw new IllegalArgumentException("every exercise needs passages in EN and FR");
    }
  }

  /** The table becomes the version: changed passages lose their embedding and get a new one. */
  @Override
  public void apply(JsonNode live, int version) {
    if (version == 0) {
      return;
    }
    Set<String> keep = new HashSet<>();
    transactions.executeWithoutResult(
        s -> {
          for (JsonNode p : live) {
            keep.add(p.path("id").asText());
            db.sql(
                    """
                    INSERT INTO companion_passage (id, exercise, locale, text, source)
                    VALUES (CAST(:id AS uuid), :e, :l, :t, :s)
                    ON CONFLICT (id) DO UPDATE
                       SET exercise = EXCLUDED.exercise, locale = EXCLUDED.locale,
                           text = EXCLUDED.text, source = EXCLUDED.source,
                           embedding = CASE WHEN companion_passage.text = EXCLUDED.text
                                            THEN companion_passage.embedding END
                    """)
                .param("id", p.path("id").asText())
                .param("e", p.path("exercise").asText())
                .param("l", p.path("locale").asText())
                .param("t", p.path("text").asText())
                .param("s", p.path("source").asText())
                .update();
          }
          db.sql("SELECT id::text FROM companion_passage").query(String.class).list().stream()
              .filter(id -> !keep.contains(id))
              .forEach(
                  id ->
                      db.sql("DELETE FROM companion_passage WHERE id = CAST(:id AS uuid)")
                          .param("id", id)
                          .update());
        });
    corpus.run(null);
  }
}
