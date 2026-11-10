package care.solace.tracking.rules;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.episodes.EpisodeModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a rule may say and read (Tracking Service Spec, What a rule is made of; D51). Rules state
 * facts against thresholds: no OR, no free-form expressions, no diagnostic wording.
 */
public final class RuleRules {

  public static final Set<String> UNLESS_FIELDS =
      Set.of("pcos_status", "trying_to_conceive", "treatments", "enrolment_answer");

  /**
   * Wording that names a condition, a severity or a prediction. "Not a diagnosis" is allowed, since
   * saying so is the point. The reviewer checks everything else.
   */
  private static final List<Pattern> BLOCKED =
      List.of(
          Pattern.compile("\\bdiagnos"),
          Pattern.compile("\\byou have\\b"),
          Pattern.compile("\\bvous avez (un|une|le|la|des)\\b"),
          Pattern.compile("\\b(disorder|disease|syndrome|illness)\\b"),
          Pattern.compile("\\b(trouble|maladie|syndrome)\\b"),
          Pattern.compile("\\b(severe|severity|serious|critical)\\b"),
          Pattern.compile("\\b(severe|gravite|grave|critique)\\b"),
          Pattern.compile("\\b(predict|prediction|forecast|likely to|risk score)\\b"),
          Pattern.compile("\\b(predire|prediction|prevision|risque de)\\b"),
          Pattern.compile("\\btriage\\b"),
          Pattern.compile("\\b(depression|depressed|depressive|anxiety disorder|bipolar)\\b"),
          Pattern.compile("\\b(depressif|depressive|bipolaire)\\b"),
          Pattern.compile("\\bpcos\\b|\\bsopk\\b"));

  private static final List<String> ALLOWED_PHRASES =
      List.of(
          "this is not a diagnosis",
          "not a diagnosis",
          "ce n'est pas un diagnostic",
          "pas un diagnostic");

  private RuleRules() {}

  /** True when the message passes the word list. */
  public static boolean wordingAllowed(String message) {
    String text = fold(message);
    for (String allowed : ALLOWED_PHRASES) {
      text = text.replace(allowed, " ");
    }
    for (Pattern p : BLOCKED) {
      if (p.matcher(text).find()) {
        return false;
      }
    }
    return true;
  }

  private static String fold(String text) {
    String noAccents =
        Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    return noAccents.toLowerCase(Locale.ROOT).replace('’', '\'');
  }

  /**
   * Problems with a rule's conditions and unless, given the module's bound elements and the
   * published episode models; empty when it is fine. Signals come from {@link SignalCatalog} and
   * operators from {@link Operators}, the same lists the builder's rule vocabulary offers (D61).
   */
  public static List<String> problems(
      JsonNode conditions,
      JsonNode unless,
      Map<String, Element> bound,
      Collection<EpisodeModel> models) {
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
    if (unless != null && unless.isArray()) {
      for (JsonNode u : unless) {
        if (!UNLESS_FIELDS.contains(u.path("field").asText())) {
          problems.add("unless: " + u.path("field").asText());
        }
      }
    }
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
