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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The published context questions (Tracking Service Spec, Context questions; D61): the newest
 * published version of each code. They change only by migration until the admin editor arrives, so
 * they are read once at start.
 */
@Component
public class ContextQuestions {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Map<String, ContextQuestion> byCode = new LinkedHashMap<>();

  @Autowired
  ContextQuestions(JdbcClient db) {
    this(
        db.sql("SELECT * FROM context_question WHERE status = 'PUBLISHED' ORDER BY code, version")
            .query(ContextQuestions::row)
            .list());
  }

  private ContextQuestions(Collection<ContextQuestion> questions) {
    questions.forEach(q -> byCode.put(q.code(), q));
  }

  /** A fixed set of questions, for tests that do not read the database. */
  public static ContextQuestions of(Collection<ContextQuestion> questions) {
    return new ContextQuestions(questions);
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

  private static ContextQuestion row(ResultSet rs, int n) throws SQLException {
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
