package care.solace.tracking.cycles;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cycles from what the person logged (Tracking Service Spec, Signals; D50). Pure: no database, so
 * the synthetic-history tests and the reconciler share it.
 *
 * <ul>
 *   <li>A cycle starts on a {@code period_start} event, or on the first day of flow above none that
 *       follows at least {@code newPeriodGapDays} days without any flow above none.
 *   <li>Its period ends on a {@code period_end} event in the cycle, otherwise on the last day of
 *       the first run of flow days; one unlogged day inside the run does not end it.
 *   <li>{@code very_heavy_days} counts days logged very_heavy in the cycle.
 *   <li>The newest cycle is never complete, so it never counts. A day with nothing logged is
 *       unknown, never none.
 * </ul>
 */
public final class CycleModel {

  /** One cycle; length is null for the newest, which is incomplete. */
  public record Cycle(
      LocalDate start,
      LocalDate end,
      LocalDate periodEnd,
      Integer lengthDays,
      Integer periodDays,
      int veryHeavyDays,
      boolean complete) {}

  private CycleModel() {}

  /**
   * @param flow flow_intensity per day: none, light, medium, heavy, very_heavy
   * @param starts days with a period_start event
   * @param ends days with a period_end event
   */
  public static List<Cycle> cycles(
      Map<LocalDate, String> flow,
      SortedSet<LocalDate> starts,
      SortedSet<LocalDate> ends,
      int newPeriodGapDays) {
    NavigableMap<LocalDate, String> days = new TreeMap<>(flow);
    SortedSet<LocalDate> cycleStarts = new TreeSet<>();
    LocalDate lastFlow = null;
    for (var day : days.entrySet()) {
      if (isFlow(day.getValue())) {
        if (lastFlow == null
            || ChronoUnit.DAYS.between(lastFlow, day.getKey()) > newPeriodGapDays) {
          cycleStarts.add(day.getKey());
        }
        lastFlow = day.getKey();
      }
    }
    // A logged start wins over an inferred one a few days around it.
    for (LocalDate s : starts) {
      cycleStarts.removeIf(
          c -> Math.abs(ChronoUnit.DAYS.between(c, s)) <= newPeriodGapDays && !starts.contains(c));
      cycleStarts.add(s);
    }
    // Two logged starts within the gap are one period started twice.
    List<LocalDate> ordered = new ArrayList<>();
    for (LocalDate s : cycleStarts) {
      if (ordered.isEmpty()
          || ChronoUnit.DAYS.between(ordered.get(ordered.size() - 1), s) > newPeriodGapDays) {
        ordered.add(s);
      }
    }

    List<Cycle> out = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      LocalDate start = ordered.get(i);
      LocalDate next = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
      LocalDate end = next == null ? null : next.minusDays(1);
      LocalDate periodEnd = periodEnd(days, ends, start, next);
      int veryHeavy = 0;
      for (var day :
          days.subMap(start, true, next == null ? LocalDate.MAX : next, false).entrySet()) {
        if ("very_heavy".equals(day.getValue())) {
          veryHeavy++;
        }
      }
      boolean complete = next != null;
      out.add(
          new Cycle(
              start,
              end,
              periodEnd,
              complete ? (int) ChronoUnit.DAYS.between(start, next) : null,
              periodEnd == null ? null : (int) ChronoUnit.DAYS.between(start, periodEnd) + 1,
              veryHeavy,
              complete));
    }
    return out;
  }

  private static LocalDate periodEnd(
      NavigableMap<LocalDate, String> days,
      SortedSet<LocalDate> ends,
      LocalDate start,
      LocalDate next) {
    for (LocalDate e : ends) {
      if (!e.isBefore(start) && (next == null || e.isBefore(next))) {
        return e;
      }
    }
    LocalDate last = null;
    LocalDate day = start;
    int unknown = 0;
    while (next == null || day.isBefore(next)) {
      String value = days.get(day);
      if (value == null) {
        // One unlogged day inside the run does not end it.
        if (++unknown > 1 || last == null) {
          break;
        }
      } else if (isFlow(value)) {
        last = day;
        unknown = 0;
      } else {
        break;
      }
      day = day.plusDays(1);
    }
    return last;
  }

  static boolean isFlow(String value) {
    return value != null && !"none".equals(value);
  }
}
