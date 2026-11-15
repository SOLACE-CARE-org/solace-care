package care.solace.tracking.context;

import care.solace.tracking.context.ContextQuestion.Option;
import care.solace.tracking.context.ContextQuestion.Scope;
import care.solace.tracking.context.ContextQuestion.Type;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The published context questions (Tracking Service Spec, Context questions; D61): the newest
 * published version of each code. Admins publish and retire questions in the portal, so this
 * instance reads them again after each change and every {@code
 * solace.tracking.elements.refresh-seconds} so other instances catch up, as the element library
 * does.
 */
@Component
public class ContextQuestions {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient db;
  private volatile Map<String, ContextQuestion> byCode;

  @Autowired
  ContextQuestions(JdbcClient db) {
    this.db = db;
    reload();
  }

  private ContextQuestions(Collection<ContextQuestion> questions) {
    this.db = null;
    this.byCode = index(questions);
  }

  /** A fixed set of questions, for tests that do not read the database. */
  public static ContextQuestions of(Collection<ContextQuestion> questions) {
    return new ContextQuestions(questions);
  }

  /**
   * Reads the published questions again; readers keep the old set until the new one is complete.
   */
  @Scheduled(
      fixedDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      initialDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      timeUnit = TimeUnit.SECONDS)
  public void reload() {
    if (db == null) {
      return;
    }
    byCode =
        index(
            db.sql(
                    "SELECT * FROM context_question WHERE status = 'PUBLISHED' ORDER BY code,"
                        + " version")
                .query(ContextQuestions::row)
                .list());
  }

  private static Map<String, ContextQuestion> index(Collection<ContextQuestion> questions) {
    Map<String, ContextQuestion> out = new LinkedHashMap<>();
    // Ordered by version, so the newest of each code is put last and wins.
    questions.forEach(q -> out.put(q.code(), q));
    return Collections.unmodifiableMap(out);
  }

  public List<ContextQuestion> all() {
    return List.copyOf(byCode.values());
  }

  public Optional<ContextQuestion> find(String code) {
    return Optional.ofNullable(byCode.get(code));
  }

  /**
   * A question from its stored JSON parts.
   *
   * @param options [{"code", "labels": {"en", "fr"}}]
   */
  public static ContextQuestion parse(
      String code, int version, String type, String scope, JsonNode labels, JsonNode options) {
    List<Option> parsed = new ArrayList<>();
    for (JsonNode o : options) {
      parsed.add(new Option(o.path("code").asText(), o.path("labels")));
    }
    return new ContextQuestion(
        code, version, Type.valueOf(type), Scope.valueOf(scope), labels, List.copyOf(parsed));
  }

  static ContextQuestion row(ResultSet rs, int n) throws SQLException {
    try {
      return parse(
          rs.getString("code"),
          rs.getInt("version"),
          rs.getString("type"),
          rs.getString("scope"),
          JSON.readTree(rs.getString("labels_json")),
          JSON.readTree(rs.getString("options_json")));
    } catch (JsonProcessingException e) {
      throw new SQLException("Bad context question JSON", e);
    }
  }
}
