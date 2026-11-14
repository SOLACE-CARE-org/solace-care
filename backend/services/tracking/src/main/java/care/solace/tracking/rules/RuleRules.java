package care.solace.tracking.rules;

import care.solace.tracking.context.ContextQuestion;
import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.episodes.EpisodeModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What a rule may say and read (Tracking Service Spec, What a rule is made of; D51). Rules state
 * facts against thresholds: no OR, no free-form expressions, no diagnostic wording.
 */
public final class RuleRules {

  private RuleRules() {}

  /**
   * Problems with a rule's conditions and unless, given the module's bound elements and the
   * published episode models and context questions; empty when it is fine. Signals come from {@link
   * SignalCatalog} and operators from {@link Operators}, the same lists the builder's rule
   * vocabulary offers (D61).
   */
  public static List<String> problems(
      JsonNode conditions,
      JsonNode unless,
      Map<String, Element> bound,
      Collection<EpisodeModel> models,
      Collection<ContextQuestion> questions) {
    List<String> problems = new ArrayList<>();
    if (conditions == null
        || !conditions.isArray()
        || conditions.isEmpty()
        || conditions.size() > 3) {
      problems.add("conditions: one to three");
      return problems;
    }
    for (JsonNode c : conditions) {
      String operatorCode = c.path("operator").asText();
      String comparison = c.path("comparison").asText();
      var operator = Operators.find(operatorCode);
      if (operator.isEmpty()) {
        problems.add("operator");
      }
      if (!Operators.comparisons().contains(comparison)) {
        problems.add("comparison");
      }
      JsonNode threshold = c.path("threshold");
      if (comparison.equals("in")
          ? !threshold.isArray() || threshold.isEmpty()
          : threshold.isMissingNode() || threshold.isNull()) {
        problems.add("threshold");
      }
      var signal = SignalCatalog.find(c, bound, models);
      if (signal.isEmpty()) {
        String name =
            c.path("signal").asText().equals("daily_value")
                ? c.path("element").asText()
                : c.path("signal").asText();
        problems.add("signal not offered by this module: " + name);
      }
      if (operator.isPresent()) {
        if (signal.isPresent()) {
          if (!operator.get().accepts(signal.get())) {
            problems.add("operator " + operatorCode + " cannot read " + signal.get().key());
          } else if (Operators.comparisons().contains(comparison)
              && !operator.get().comparisons(signal.get()).contains(comparison)) {
            problems.add("comparison " + comparison + " cannot be used here");
          }
        }
        problems.addAll(operator.get().problems(c));
      }
    }
    problems.addAll(Unless.problems(unless, questions));
    return problems;
  }

  /**
   * Fills {n}, {threshold}, {window}, {count}, {average} and {logged} from the evidence, and
   * {c1.n}, {c2.threshold} and so on for rules with several conditions.
   */
  public static String render(String template, Map<String, Object> evidence) {
    String out = template;
    for (var e : evidence.entrySet()) {
      Object v = e.getValue();
      String text =
          v instanceof JsonNode node
              ? (node.isArray() ? node.toString() : node.asText())
              : String.valueOf(v);
      out = out.replace("{" + e.getKey() + "}", text);
    }
    return out;
  }
}
