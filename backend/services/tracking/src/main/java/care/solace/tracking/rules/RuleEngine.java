package care.solace.tracking.rules;

import care.solace.tracking.cycles.CycleModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * Checks a rule's conditions against a person's signals (Tracking Service Spec, Rules and
 * thresholds; D51). Pure, so live checks and builder test runs agree. Every threshold comes from
 * the rule's row; nothing here is a clinical number.
 *
 * <p>A condition: {@code signal} (daily_value of an {@code element}, or a cycle signal), {@code
 * operator} (consecutive_cycles, days_in_window, average_in_window), {@code comparison} (gt, gte,
 * lt, lte, eq, in), {@code threshold} (a number, or values for in), {@code count} (cycles, or days
 * for days_in_window), {@code mode} (at_least or at_most, days_in_window), {@code window} (days),
 * {@code minData} (logged days needed in the window).
 */
public final class RuleEngine {

  public static final Set<String> CYCLE_SIGNALS =
      Set.of("cycle_length_days", "period_days", "very_heavy_days");
  public static final Set<String> OPERATORS =
      Set.of("consecutive_cycles", "days_in_window", "average_in_window");
  public static final Set<String> COMPARISONS = Set.of("gt", "gte", "lt", "lte", "eq", "in");

  /** What the checker reads: daily values per element (unlogged days absent), and cycles. */
  public record Signals(
      LocalDate today,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      List<CycleModel.Cycle> cycles) {}

  /** The outcome of one condition, and the exact cycles or days it used. */
  public record ConditionResult(boolean held, Map<String, Object> evidence) {}

  public record Result(boolean held, List<ConditionResult> conditions) {

    /** The first condition's evidence, for the message's {n}, {threshold} and the chart. */
    public Map<String, Object> evidence() {
      return conditions.isEmpty() ? Map.of() : conditions.get(0).evidence();
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
    String operator = c.path("operator").asText();
    return switch (operator) {
      case "consecutive_cycles" -> consecutive(c, s);
      case "days_in_window" -> daysInWindow(c, s);
      case "average_in_window" -> average(c, s);
      default -> new ConditionResult(false, Map.of("error", "unknown operator"));
    };
  }

  /**
   * The last N complete cycles each meet the comparison. The newest, incomplete one never counts.
   */
  private static ConditionResult consecutive(JsonNode c, Signals s) {
    int n = c.path("count").asInt();
    String signal = c.path("signal").asText();
    var complete = s.cycles().stream().filter(CycleModel.Cycle::complete).toList();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("n", n);
    evidence.put("threshold", c.path("threshold"));
    if (n < 1 || complete.size() < n) {
      evidence.put("cycles", List.of());
      return new ConditionResult(false, evidence);
    }
    var last = complete.subList(complete.size() - n, complete.size());
    List<Map<String, Object>> used = new ArrayList<>();
    boolean held = true;
    for (var cycle : last) {
      Integer value =
          switch (signal) {
            case "cycle_length_days" -> cycle.lengthDays();
            case "period_days" -> cycle.periodDays();
            case "very_heavy_days" -> cycle.veryHeavyDays();
            default -> null;
          };
      used.add(
          Map.of("start", cycle.start().toString(), "value", value == null ? "unknown" : value));
      held &= value != null && compare(value, c.path("comparison").asText(), c.path("threshold"));
    }
    evidence.put("cycles", used);
    return new ConditionResult(held, evidence);
  }

  /** At least, or at most, N of the last W days meet the comparison, with enough days logged. */
  private static ConditionResult daysInWindow(JsonNode c, Signals s) {
    var window = window(c, s);
    int meeting = 0;
    List<String> days = new ArrayList<>();
    for (var e : window.entrySet()) {
      if (compare(e.getValue(), c.path("comparison").asText(), c.path("threshold"))) {
        meeting++;
        days.add(e.getKey().toString());
      }
    }
    int n = c.path("count").asInt();
    boolean atMost = "at_most".equals(c.path("mode").asText("at_least"));
    boolean enough = window.size() >= c.path("minData").asInt(0);
    boolean held = enough && (atMost ? meeting <= n : meeting >= n);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("n", meeting);
    evidence.put("count", n);
    evidence.put("window", c.path("window").asInt());
    evidence.put("logged", window.size());
    evidence.put("threshold", c.path("threshold"));
    evidence.put("days", days);
    return new ConditionResult(held, evidence);
  }

  /** The average over the logged days of the last W days meets the comparison. */
  private static ConditionResult average(JsonNode c, Signals s) {
    var window = window(c, s);
    double sum = 0;
    int numbers = 0;
    for (Object v : window.values()) {
      if (v instanceof Number number) {
        sum += number.doubleValue();
        numbers++;
      }
    }
    boolean enough = numbers > 0 && numbers >= c.path("minData").asInt(0);
    double avg = numbers == 0 ? 0 : sum / numbers;
    boolean held = enough && compare(avg, c.path("comparison").asText(), c.path("threshold"));
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("average", Math.round(avg * 10) / 10.0);
    evidence.put("window", c.path("window").asInt());
    evidence.put("logged", numbers);
    evidence.put("threshold", c.path("threshold"));
    return new ConditionResult(held, evidence);
  }

  /** Logged days of the element in the last W days, today included. Unlogged days are absent. */
  private static NavigableMap<LocalDate, Object> window(JsonNode c, Signals s) {
    var daily = s.daily().get(c.path("element").asText());
    if (daily == null) {
      return new java.util.TreeMap<>();
    }
    int w = Math.max(1, c.path("window").asInt(1));
    return daily.subMap(s.today().minusDays(w - 1L), true, s.today(), true);
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
