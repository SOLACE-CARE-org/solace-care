package care.solace.tracking.context;

import care.solace.tracking.context.ContextQuestion.Option;
import care.solace.tracking.context.ContextQuestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a context question draft must be before review (Tracking Service Spec, Context questions;
 * D61). Pure: the published version of the same code, if any, is passed in. Each problem is a key
 * the portal turns into words.
 */
public final class ContextQuestionRules {

  public static final int MAX_QUESTION = 200;
  public static final int MAX_OPTION = 60;
  public static final int MAX_OPTIONS = 12;

  private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]*$");

  private ContextQuestionRules() {}

  /**
   * @param published the newest published version of the code, or null
   * @return in order: type, question, options; empty when the draft can go to review
   */
  public static List<String> problems(ContextQuestion d, ContextQuestion published) {
    List<String> problems = new ArrayList<>();
    // Stored answers and rules read the old type and options, so a new version keeps them.
    if (published != null && published.type() != d.type()) {
      problems.add("type");
    }
    JsonNode labels = d.labels();
    if (!text(labels.path("en").path("question"), MAX_QUESTION)
        || !text(labels.path("fr").path("question"), MAX_QUESTION)) {
      problems.add("question");
    }
    if (!optionsFine(d, published)) {
      problems.add("options");
    }
    return problems;
  }

  private static boolean optionsFine(ContextQuestion d, ContextQuestion published) {
    List<Option> options = d.options();
    if (d.type() == Type.YES_NO) {
      return options.isEmpty();
    }
    if (options.size() < 2 || options.size() > MAX_OPTIONS) {
      return false;
    }
    Set<String> codes = new HashSet<>();
    for (Option o : options) {
      if (o.code() == null
          || o.code().length() > 40
          || !CODE.matcher(o.code()).matches()
          || !codes.add(o.code())
          || !text(o.labels().path("en"), MAX_OPTION)
          || !text(o.labels().path("fr"), MAX_OPTION)) {
        return false;
      }
    }
    if (published != null) {
      for (Option o : published.options()) {
        if (!codes.contains(o.code())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean text(JsonNode node, int max) {
    return node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max;
  }
}
