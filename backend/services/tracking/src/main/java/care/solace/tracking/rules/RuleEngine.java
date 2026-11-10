package care.solace.tracking.rules;

import care.solace.tracking.episodes.Episode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Checks a rule's conditions against a person's signals (Tracking Service Spec, Rules and
 * thresholds; D51). Pure, so live checks and builder test runs agree. Every threshold comes from
 * the rule's row; nothing here is a clinical number. Each condition is read by its operator from
 * the {@link Operators} registry (D61).
 *
 * <p>A condition: {@code signal} (daily_value of an {@code element}, or an episode signal such as
 * {@code menstrual_cycle.length_days}), {@code operator} (consecutive_episodes, days_in_window,
 * average_in_window), {@code comparison} (gt, gte, lt, lte, eq, in), {@code threshold} (a number,
 * or values for in), {@code count} (episodes, or days for days_in_window), {@code mode} (at_least
 * or at_most, days_in_window), {@code window} (days), {@code minData} (logged days needed in the
 * window).
 */
public final class RuleEngine {

  /**
   * What the checker reads: daily values per element (unlogged days absent), and episodes per
   * episode model, oldest first.
   */
  public record Signals(
      LocalDate today,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      Map<String, List<Episode>> episodes) {

    public List<Episode> episodes(String model) {
      return episodes.getOrDefault(model, List.of());
    }
  }

  /** The outcome of one condition, and the exact episodes or days it used. */
  public record ConditionResult(boolean held, Map<String, Object> evidence) {}

  public record Result(boolean held, List<ConditionResult> conditions) {

    /**
     * The evidence for the message and the chart: the first condition's keys as they are ({n},
     * {threshold}, ...), and, when there are several conditions, every condition's keys again as
     * {c1.n}, {c2.threshold} and so on (D61).
     */
    public Map<String, Object> evidence() {
      if (conditions.isEmpty()) {
        return Map.of();
      }
      Map<String, Object> out = new LinkedHashMap<>(conditions.get(0).evidence());
      if (conditions.size() > 1) {
        for (int i = 0; i < conditions.size(); i++) {
          for (var e : conditions.get(i).evidence().entrySet()) {
            out.put("c" + (i + 1) + "." + e.getKey(), e.getValue());
          }
        }
      }
      return out;
    }
  }

  private RuleEngine() {}

  public static Result evaluate(JsonNode conditions, Signals signals) {
    List<ConditionResult> results = new ArrayList<>();
    boolean all = conditions.size() > 0;
    for (JsonNode c : conditions) {
      var r = condition(c, signals);
      results.add(r);
      all &= r.held();
    }
    return new Result(all, results);
  }

  static ConditionResult condition(JsonNode c, Signals s) {
    return Operators.find(c.path("operator").asText())
        .map(o -> o.evaluate(c, s))
        .orElseGet(() -> new ConditionResult(false, Map.of("error", "unknown operator")));
  }

  static boolean compare(Object value, String comparison, JsonNode threshold) {
    if ("in".equals(comparison)) {
      for (JsonNode t : threshold) {
        if (t.asText().equals(String.valueOf(value))) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof Number n && threshold.isNumber()) {
      double v = n.doubleValue();
      double t = threshold.asDouble();
      return switch (comparison) {
        case "gt" -> v > t;
        case "gte" -> v >= t;
        case "lt" -> v < t;
        case "lte" -> v <= t;
        case "eq" -> v == t;
        default -> false;
      };
    }
    if ("eq".equals(comparison)) {
      return threshold.asText().equals(String.valueOf(value));
    }
    return false;
  }
}
