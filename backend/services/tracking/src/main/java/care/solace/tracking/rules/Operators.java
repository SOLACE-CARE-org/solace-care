package care.solace.tracking.rules;

import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.Episode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The operator registry (D61): the three operators of the Tracking Service Spec. Adding one is a
 * reviewed code change with its own tests; the builder picks it up from the rule vocabulary.
 */
public final class Operators {

  static final List<String> NUMBER_COMPARISONS = List.of("gt", "gte", "lt", "lte", "eq");
  static final List<String> ENUM_COMPARISONS = List.of("eq", "in");
  static final List<String> BOOL_COMPARISONS = List.of("eq");

  private static final Map<String, Operator> ALL = new LinkedHashMap<>();

  static {
    for (Operator o :
        List.of(new ConsecutiveEpisodes(), new DaysInWindow(), new AverageInWindow())) {
      ALL.put(o.code(), o);
    }
  }

  private Operators() {}

  public static List<Operator> all() {
    return List.copyOf(ALL.values());
  }

  public static Optional<Operator> find(String code) {
    return Optional.ofNullable(ALL.get(code));
  }

  /** Every comparison any operator allows. */
  public static Set<String> comparisons() {
    return Set.of("gt", "gte", "lt", "lte", "eq", "in");
  }

  /**
   * The last N complete episodes of the signal's model each meet the comparison; the newest episode
   * never counts.
   */
  static final class ConsecutiveEpisodes implements Operator {

    @Override
    public String code() {
      return "consecutive_episodes";
    }

    @Override
    public boolean accepts(SignalCatalog.Signal signal) {
      return signal.kind() == SignalCatalog.Kind.EPISODE;
    }

    @Override
    public List<String> comparisons(SignalCatalog.Signal signal) {
      return NUMBER_COMPARISONS;
    }

    @Override
    public List<String> fields() {
      return List.of("count");
    }

    @Override
    public List<String> evidenceKeys() {
      return List.of("n", "threshold");
    }

    @Override
    public List<String> problems(JsonNode c) {
      return c.path("count").asInt(0) < 1
          ? List.of("consecutive_episodes needs a count of at least 1")
          : List.of();
    }

    @Override
    public RuleEngine.ConditionResult evaluate(JsonNode c, RuleEngine.Signals s) {
      int n = c.path("count").asInt();
      String signal = c.path("signal").asText();
      int dot = signal.indexOf('.');
      String model = dot < 0 ? signal : signal.substring(0, dot);
      String metric = dot < 0 ? "" : signal.substring(dot + 1);
      var complete = s.episodes(model).stream().filter(Episode::complete).toList();
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("n", n);
      evidence.put("threshold", c.path("threshold"));
      if (n < 1 || complete.size() < n) {
        evidence.put("episodes", List.of());
        return new RuleEngine.ConditionResult(false, evidence);
      }
      List<Map<String, Object>> used = new ArrayList<>();
      boolean held = true;
      for (var episode : complete.subList(complete.size() - n, complete.size())) {
        Integer value = episode.metric(metric);
        used.add(
            Map.of(
                "start", episode.start().toString(), "value", value == null ? "unknown" : value));
        held &=
            value != null
                && RuleEngine.compare(value, c.path("comparison").asText(), c.path("threshold"));
      }
      evidence.put("episodes", used);
      return new RuleEngine.ConditionResult(held, evidence);
    }
  }

  /** Shared checks for the operators that read daily values over the last W days. */
  abstract static class WindowOperator implements Operator {

    @Override
    public List<String> problems(JsonNode c) {
      List<String> problems = new ArrayList<>();
      int window = c.path("window").asInt(0);
      if (window < 1 || window > 365) {
        problems.add("window: 1 to 365 days");
      }
      if (c.path("minData").asInt(0) > window) {
        problems.add("minimum data cannot exceed the window");
      }
      return problems;
    }

    /** Logged days of the element in the last W days, today included. Unlogged days are absent. */
    static NavigableMap<java.time.LocalDate, Object> window(JsonNode c, RuleEngine.Signals s) {
      var daily = s.daily().get(c.path("element").asText());
      if (daily == null) {
        return new TreeMap<>();
      }
      int w = Math.max(1, c.path("window").asInt(1));
      return daily.subMap(s.today().minusDays(w - 1L), true, s.today(), true);
    }
  }

  /** At least, or at most, N of the last W days meet the comparison, with enough days logged. */
  static final class DaysInWindow extends WindowOperator {

    @Override
    public String code() {
      return "days_in_window";
    }

    @Override
    public boolean accepts(SignalCatalog.Signal signal) {
      return signal.kind() == SignalCatalog.Kind.DAILY;
    }

    @Override
    public List<String> comparisons(SignalCatalog.Signal signal) {
      if (signal.valueType() == ValueType.ENUM) {
        return ENUM_COMPARISONS;
      }
      if (signal.valueType() == ValueType.BOOL) {
        return BOOL_COMPARISONS;
      }
      return NUMBER_COMPARISONS;
    }

    @Override
    public List<String> fields() {
      return List.of("mode", "count", "window", "minData");
    }

    @Override
    public List<String> evidenceKeys() {
      return List.of("n", "count", "window", "logged", "threshold");
    }

    @Override
    public List<String> problems(JsonNode c) {
      List<String> problems = new ArrayList<>(super.problems(c));
      if (c.path("count").asInt(-1) < 0
          || !Set.of("at_least", "at_most").contains(c.path("mode").asText())) {
        problems.add("days_in_window needs a count and at_least or at_most");
      }
      return problems;
    }

    @Override
    public RuleEngine.ConditionResult evaluate(JsonNode c, RuleEngine.Signals s) {
      var window = window(c, s);
      int meeting = 0;
      List<String> days = new ArrayList<>();
      for (var e : window.entrySet()) {
        if (RuleEngine.compare(e.getValue(), c.path("comparison").asText(), c.path("threshold"))) {
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
      return new RuleEngine.ConditionResult(held, evidence);
    }
  }

  /** The average over the logged days of the last W days meets the comparison. Numbers only. */
  static final class AverageInWindow extends WindowOperator {

    @Override
    public String code() {
      return "average_in_window";
    }

    @Override
    public boolean accepts(SignalCatalog.Signal signal) {
      return signal.kind() == SignalCatalog.Kind.DAILY && signal.numeric();
    }

    @Override
    public List<String> comparisons(SignalCatalog.Signal signal) {
      return NUMBER_COMPARISONS;
    }

    @Override
    public List<String> fields() {
      return List.of("window", "minData");
    }

    @Override
    public List<String> evidenceKeys() {
      return List.of("average", "window", "logged", "threshold");
    }

    @Override
    public RuleEngine.ConditionResult evaluate(JsonNode c, RuleEngine.Signals s) {
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
      boolean held =
          enough && RuleEngine.compare(avg, c.path("comparison").asText(), c.path("threshold"));
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("average", Math.round(avg * 10) / 10.0);
      evidence.put("window", c.path("window").asInt());
      evidence.put("logged", numbers);
      evidence.put("threshold", c.path("threshold"));
      return new RuleEngine.ConditionResult(held, evidence);
    }
  }
}
