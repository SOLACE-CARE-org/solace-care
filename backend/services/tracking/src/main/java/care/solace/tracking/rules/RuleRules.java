package care.solace.tracking.rules;

import care.solace.tracking.elements.ElementLibrary.Element;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
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

  private record Wording(List<Pattern> blocked, List<String> allowed) {}

  /** Version 0 is the lists above; clinicians change them as tracking.rules.wording (D61). */
  private static volatile Wording inForce = new Wording(BLOCKED, ALLOWED_PHRASES);

  private RuleRules() {}

  public static List<String> blockedDefaults() {
    return BLOCKED.stream().map(Pattern::pattern).toList();
  }

  public static List<String> allowedDefaults() {
    return ALLOWED_PHRASES;
  }

  /** Puts a signed-off version of the word list in force. */
  public static void useWording(List<String> blocked, List<String> allowed) {
    inForce = new Wording(blocked.stream().map(Pattern::compile).toList(), List.copyOf(allowed));
  }

  /** True when the message passes the word list. */
  public static boolean wordingAllowed(String message) {
    String text = fold(message);
    var wording = inForce;
    for (String allowed : wording.allowed()) {
      text = text.replace(allowed, " ");
    }
    for (Pattern p : wording.blocked()) {
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
   * Problems with a rule's conditions and unless, given the module's bound elements; empty when it
   * is fine.
   */
  /**
   * An element read day by day: one that offers daily_value, or a cycle element, whose daily value
   * the launch rule pcos.no_period counts (Tracking Service Spec, Launch rules; D54).
   */
  public static boolean offersDailyValue(Element element) {
    return element.signals().contains("daily_value")
        || element.signals().stream().anyMatch(RuleEngine.CYCLE_SIGNALS::contains);
  }

  public static List<String> problems(
      JsonNode conditions, JsonNode unless, Map<String, Element> bound) {
    List<String> problems = new ArrayList<>();
    if (conditions == null
        || !conditions.isArray()
        || conditions.isEmpty()
        || conditions.size() > 3) {
      problems.add("conditions: one to three");
      return problems;
    }
    for (JsonNode c : conditions) {
      String signal = c.path("signal").asText();
      String operator = c.path("operator").asText();
      String comparison = c.path("comparison").asText();
      if (!RuleEngine.OPERATORS.contains(operator)) {
        problems.add("operator");
      }
      if (!RuleEngine.COMPARISONS.contains(comparison)) {
        problems.add("comparison");
      }
      JsonNode threshold = c.path("threshold");
      if (comparison.equals("in")
          ? !threshold.isArray() || threshold.isEmpty()
          : threshold.isMissingNode() || threshold.isNull()) {
        problems.add("threshold");
      }
      if (RuleEngine.CYCLE_SIGNALS.contains(signal)) {
        Element flow = bound.get("flow_intensity");
        if (flow == null || !flow.signals().contains(signal)) {
          problems.add("signal not offered by this module: " + signal);
        }
        if (!operator.equals("consecutive_cycles") || c.path("count").asInt(0) < 1) {
          problems.add("cycle signals use consecutive_cycles with a count");
        }
      } else if (signal.equals("daily_value")) {
        Element element = bound.get(c.path("element").asText());
        if (element == null || !offersDailyValue(element)) {
          problems.add("signal not offered by this module: " + c.path("element").asText());
        }
        if (operator.equals("consecutive_cycles")) {
          problems.add("daily values use a window operator");
        }
        int window = c.path("window").asInt(0);
        if (window < 1 || window > 365) {
          problems.add("window: 1 to 365 days");
        }
        if (c.path("minData").asInt(0) > window) {
          problems.add("minimum data cannot exceed the window");
        }
        if (operator.equals("days_in_window")
            && (c.path("count").asInt(-1) < 0
                || !Set.of("at_least", "at_most").contains(c.path("mode").asText()))) {
          problems.add("days_in_window needs a count and at_least or at_most");
        }
      } else {
        problems.add("signal");
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

  /** Fills {n}, {threshold}, {window}, {count}, {average} and {logged} from the evidence. */
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
