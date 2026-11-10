package care.solace.tracking.rules;

import care.solace.tracking.context.ContextQuestion;
import care.solace.tracking.context.ContextQuestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A rule's "unless": answers to published context questions that switch it off (Tracking Service
 * Spec, What a rule is made of; D61). They can never switch a rule on, and an unanswered question
 * switches nothing off.
 *
 * <p>An entry is {@code {"question": <code>, "equals": <answer>}} or {@code {"question": <code>,
 * "includes": <option>}}. Entries written before context questions name a {@code field} of the old
 * tracking profile instead; they are read through the questions that replaced it.
 */
public final class Unless {

  /** The old profile fields, still read for rules drafted before context questions. */
  public static final Set<String> LEGACY_FIELDS =
      Set.of("pcos_status", "trying_to_conceive", "treatments", "enrolment_answer");

  private Unless() {}

  /**
   * @param answers the patient's context answers by question code
   * @param enrolmentAnswer the answer to a module's old single enrolment question, if any
   */
  public static boolean blocks(
      JsonNode unless, Map<String, JsonNode> answers, String enrolmentAnswer) {
    if (unless == null || !unless.isArray()) {
      return false;
    }
    for (JsonNode u : unless) {
      if (u.has("question")) {
        if (matches(u, answers.get(u.path("question").asText()))) {
          return true;
        }
        continue;
      }
      switch (u.path("field").asText()) {
        case "enrolment_answer" -> {
          if (enrolmentAnswer != null && matches(u, TextNode.valueOf(enrolmentAnswer))) {
            return true;
          }
        }
        case "trying_to_conceive" -> {
          if (matches(u, answers.get("trying_to_conceive"))) {
            return true;
          }
        }
        case "treatments" -> {
          // The old treatments list held "hormonal"; that is now its own yes/no question.
          if ("hormonal".equals(u.path("includes").asText())
              && BooleanNode.TRUE.equals(answers.get("hormonal_treatment"))) {
            return true;
          }
        }
        default -> {
          // pcos_status has no question: it never switches a rule off.
        }
      }
    }
    return false;
  }

  private static boolean matches(JsonNode u, JsonNode answer) {
    if (answer == null || answer.isNull() || answer.isMissingNode()) {
      return false;
    }
    if (u.has("equals")) {
      return u.path("equals").equals(answer);
    }
    if (u.has("includes")) {
      String option = u.path("includes").asText();
      if (answer.isArray()) {
        for (JsonNode a : answer) {
          if (a.asText().equals(option)) {
            return true;
          }
        }
        return false;
      }
      return answer.asText().equals(option);
    }
    return false;
  }

  /** Problems with a rule's "unless", given the published questions; empty when it is fine. */
  public static List<String> problems(JsonNode unless, Collection<ContextQuestion> questions) {
    List<String> problems = new ArrayList<>();
    if (unless == null || unless.isNull() || unless.isMissingNode()) {
      return problems;
    }
    if (!unless.isArray()) {
      problems.add("unless: a list");
      return problems;
    }
    for (JsonNode u : unless) {
      if (!u.has("question")) {
        if (!LEGACY_FIELDS.contains(u.path("field").asText())) {
          problems.add("unless: " + u.path("field").asText());
        }
        continue;
      }
      String code = u.path("question").asText();
      Optional<ContextQuestion> question =
          questions.stream().filter(q -> q.code().equals(code)).findFirst();
      if (question.isEmpty()) {
        problems.add("unless: no published question " + code);
        continue;
      }
      var q = question.get();
      boolean fits =
          u.has("equals")
              ? q.type() != Type.MANY_CHOICES && q.accepts(u.path("equals"))
              : u.has("includes")
                  && q.type() != Type.YES_NO
                  && q.hasOption(u.path("includes").asText());
      if (!fits) {
        problems.add("unless: not an answer to " + code);
      }
    }
    return problems;
  }
}
