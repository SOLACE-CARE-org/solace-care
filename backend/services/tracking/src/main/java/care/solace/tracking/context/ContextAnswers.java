package care.solace.tracking.context;

import care.solace.tracking.PersonalData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A patient's answers to context questions, by question code (Tracking Service Spec, Context
 * questions). They stay in Tracking, are never sent anywhere, can only switch rules off, and are
 * erased with everything else.
 */
@Component
public class ContextAnswers implements PersonalData {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient db;
  private final Clock clock;

  ContextAnswers(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Every answer the patient has given; a skipped question is absent. */
  public Map<String, JsonNode> answers(UUID user) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    db.sql(
            "SELECT question_code, value_json FROM context_answer WHERE user_id = :u ORDER BY question_code")
        .param("u", user)
        .query((rs, n) -> Map.entry(rs.getString("question_code"), rs.getString("value_json")))
        .list()
        .forEach(e -> out.put(e.getKey(), tree(e.getValue())));
    return out;
  }

  /** Saves an answer the caller has checked with {@link ContextQuestion#accepts}. */
  public void save(UUID user, String questionCode, JsonNode value) {
    db.sql(
            """
            INSERT INTO context_answer (user_id, question_code, value_json, answered_at)
            VALUES (:u, :q, CAST(:v AS jsonb), :now)
            ON CONFLICT (user_id, question_code) DO UPDATE
               SET value_json = EXCLUDED.value_json, answered_at = EXCLUDED.answered_at
            """)
        .param("u", user)
        .param("q", questionCode)
        .param("v", value.toString())
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  /** Skipping a question after answering it removes the answer. */
  public void clear(UUID user, String questionCode) {
    db.sql("DELETE FROM context_answer WHERE user_id = :u AND question_code = :q")
        .param("u", user)
        .param("q", questionCode)
        .update();
  }

  private static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Bad context answer", e);
    }
  }

  @Override
  public void erase(UUID userId) {
    db.sql("DELETE FROM context_answer WHERE user_id = :u").param("u", userId).update();
  }
}
