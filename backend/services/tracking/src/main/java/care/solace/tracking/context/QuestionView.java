package care.solace.tracking.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** A published context question as the app and the portal see it (D61). */
public record QuestionView(
    String code,
    int version,
    String type,
    String scope,
    JsonNode labels,
    List<ContextQuestion.Option> options) {

  public static QuestionView of(ContextQuestion q) {
    return new QuestionView(
        q.code(), q.version(), q.type().name(), q.scope().name(), q.labels(), q.options());
  }
}
