package care.solace.tracking.rules;

import care.solace.tracking.cycles.CycleModel;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Made-up patient histories the builder tests drafts against (Tracking Service Spec, Testing before
 * publishing). Real patient data is never used. Each history is built back from today, so window
 * rules see it as recent.
 */
public final class SyntheticHistories {

  /** One made-up person: what they logged, and their profile for "unless". */
  public record History(
      String code,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      SortedSet<LocalDate> starts,
      SortedSet<LocalDate> ends,
      Map<String, Object> profile) {

    public RuleEngine.Signals signals(LocalDate today, int gapDays) {
      @SuppressWarnings("unchecked")
      Map<LocalDate, String> flow = new TreeMap<>();
      daily
          .getOrDefault("flow_intensity", new TreeMap<>())
          .forEach((d, v) -> flow.put(d, String.valueOf(v)));
      List<CycleModel.Cycle> cycles = CycleModel.cycles(flow, starts, ends, gapDays);
      return new RuleEngine.Signals(today, daily, cycles);
    }
  }

  private SyntheticHistories() {}

  public static List<History> all(LocalDate today) {
    return List.of(
        cycles("regular_cycles", today, new int[] {28, 29, 27, 28, 28}, 5, 0, Map.of()),
        cycles("long_cycles", today, new int[] {41, 39, 44, 40}, 5, 0, Map.of()),
        cycles(
            "long_cycles_hormonal_treatment",
            today,
            new int[] {41, 39, 44, 40},
            5,
            0,
            Map.of("treatments", List.of("hormonal"))),
        cycles("long_periods", today, new int[] {30, 31, 29}, 9, 0, Map.of()),
        cycles("heavy_flow", today, new int[] {28, 30, 29}, 6, 4, Map.of()),
        noPeriod("no_period_90_days", today, Map.of()),
        noPeriod("no_period_trying_to_conceive", today, Map.of("trying_to_conceive", true)),
        daily("low_mood_two_weeks", today, "mood", 14, i -> i % 7 == 3 ? 3 : 2),
        daily("good_mood", today, "mood", 14, i -> 4),
        daily("short_sleep", today, "sleep_minutes", 14, i -> 270),
        daily("enough_sleep", today, "sleep_minutes", 14, i -> 450),
        sparse("sparse_logging", today));
  }

  private static History cycles(
      String code,
      LocalDate today,
      int[] lengths,
      int periodDays,
      int veryHeavy,
      Map<String, Object> profile) {
    NavigableMap<LocalDate, Object> flow = new TreeMap<>();
    int total = 0;
    for (int l : lengths) {
      total += l;
    }
    // The last cycle starts a few days ago and is incomplete, as in life.
    LocalDate start = today.minusDays(total + 5L);
    SortedSet<LocalDate> starts = new TreeSet<>();
    for (int i = 0; i <= lengths.length; i++) {
      starts.add(start);
      for (int d = 0; d < periodDays; d++) {
        flow.put(start.plusDays(d), d < veryHeavy ? "very_heavy" : "medium");
      }
      for (int d = periodDays;
          d < Math.min(periodDays + 3, i < lengths.length ? lengths[i] : 0);
          d++) {
        flow.put(start.plusDays(d), "none");
      }
      if (i < lengths.length) {
        start = start.plusDays(lengths[i]);
      }
    }
    Map<String, NavigableMap<LocalDate, Object>> daily = new LinkedHashMap<>();
    daily.put("flow_intensity", flow);
    return new History(code, daily, starts, new TreeSet<>(), profile);
  }

  private static History noPeriod(String code, LocalDate today, Map<String, Object> profile) {
    NavigableMap<LocalDate, Object> flow = new TreeMap<>();
    for (int i = 0; i < 90; i++) {
      if (i % 2 == 0) {
        flow.put(today.minusDays(i), "none");
      }
    }
    return new History(
        code,
        new LinkedHashMap<>(Map.of("flow_intensity", flow)),
        new TreeSet<>(),
        new TreeSet<>(),
        profile);
  }

  private static History daily(
      String code,
      LocalDate today,
      String element,
      int days,
      java.util.function.IntFunction<Object> value) {
    NavigableMap<LocalDate, Object> values = new TreeMap<>();
    for (int i = 0; i < days; i++) {
      values.put(today.minusDays(i), value.apply(i));
    }
    return new History(
        code,
        new LinkedHashMap<>(Map.of(element, values)),
        new TreeSet<>(),
        new TreeSet<>(),
        Map.of());
  }

  private static History sparse(String code, LocalDate today) {
    NavigableMap<LocalDate, Object> mood = new TreeMap<>();
    NavigableMap<LocalDate, Object> sleep = new TreeMap<>();
    for (int i = 0; i < 14; i += 3) {
      mood.put(today.minusDays(i), 1);
      sleep.put(today.minusDays(i), 200);
    }
    return new History(
        code,
        new LinkedHashMap<>(Map.of("mood", mood, "sleep_minutes", sleep)),
        new TreeSet<>(),
        new TreeSet<>(),
        Map.of());
  }
}
