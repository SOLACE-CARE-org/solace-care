package care.solace.tracking.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The twelve histories exactly as Java built them before they became recipes (D61), kept only to
 * prove the seeded recipes build the same histories.
 */
final class LegacyHistories {

  record History(
      String code,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      Map<String, SortedSet<LocalDate>> events,
      Map<String, JsonNode> answers) {}

  private LegacyHistories() {}

  static List<History> all(LocalDate today) {
    return List.of(
        cycles("regular_cycles", today, new int[] {28, 29, 27, 28, 28}, 5, 0, Map.of()),
        cycles("long_cycles", today, new int[] {41, 39, 44, 40}, 5, 0, Map.of()),
        cycles(
            "long_cycles_hormonal_treatment",
            today,
            new int[] {41, 39, 44, 40},
            5,
            0,
            Map.of("hormonal_treatment", BooleanNode.TRUE)),
        cycles("long_periods", today, new int[] {30, 31, 29}, 9, 0, Map.of()),
        cycles("heavy_flow", today, new int[] {28, 30, 29}, 6, 4, Map.of()),
        noPeriod("no_period_90_days", today, Map.of()),
        noPeriod(
            "no_period_trying_to_conceive", today, Map.of("trying_to_conceive", BooleanNode.TRUE)),
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
      Map<String, JsonNode> answers) {
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
    return new History(code, daily, Map.of("period_start", starts), answers);
  }

  private static History noPeriod(String code, LocalDate today, Map<String, JsonNode> answers) {
    NavigableMap<LocalDate, Object> flow = new TreeMap<>();
    for (int i = 0; i < 90; i++) {
      if (i % 2 == 0) {
        flow.put(today.minusDays(i), "none");
      }
    }
    return new History(
        code, new LinkedHashMap<>(Map.of("flow_intensity", flow)), Map.of(), answers);
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
    return new History(code, new LinkedHashMap<>(Map.of(element, values)), Map.of(), Map.of());
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
        Map.of(),
        Map.of());
  }
}
