package care.solace.tracking.rules;

import care.solace.tracking.cycles.CycleModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The numbers the cycle model produces per complete cycle, by signal code (Tracking Service Spec,
 * Signals; D50, D61). The one place that maps a cycle signal to a cycle field; the episode models
 * of Phase 2b step 2 replace it with reviewed definitions.
 */
public final class CycleMetrics {

  private static final Map<String, Function<CycleModel.Cycle, Integer>> METRICS =
      new LinkedHashMap<>();

  static {
    METRICS.put("cycle_length_days", CycleModel.Cycle::lengthDays);
    METRICS.put("period_days", CycleModel.Cycle::periodDays);
    METRICS.put("very_heavy_days", CycleModel.Cycle::veryHeavyDays);
  }

  private CycleMetrics() {}

  /** Every cycle signal code, in display order. */
  public static Set<String> codes() {
    return METRICS.keySet();
  }

  public static boolean isCycleSignal(String code) {
    return METRICS.containsKey(code);
  }

  /** The cycle's value for a signal; null when unknown or not a cycle signal. */
  public static Integer value(String code, CycleModel.Cycle cycle) {
    var metric = METRICS.get(code);
    return metric == null ? null : metric.apply(cycle);
  }
}
