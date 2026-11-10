package care.solace.tracking.episodes;

import care.solace.tracking.episodes.EpisodeModel.Span;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The episode algorithm (Tracking Service Spec, Episode models; D61). Code, not data: every model
 * is read the same way. Pure, so the reconciler and the builder's test runs share it.
 *
 * <ul>
 *   <li>An episode starts on a logged start, or on the first active day that follows more than the
 *       model's gap days without activity. A logged start wins over an inferred one within the gap,
 *       and two starts within the gap are one episode.
 *   <li>The active run ends on a logged end in the episode, otherwise on the last active day of the
 *       first run; one unlogged day inside the run does not end it.
 *   <li>A day with nothing logged is unknown, never inactive. The newest episode is never complete,
 *       so it never counts.
 * </ul>
 */
public final class Episodes {

  private Episodes() {}

  /**
   * @param activity the activity element's value per day
   * @param starts days with a start event
   * @param ends days with an end event
   */
  public static List<Episode> find(
      EpisodeModel model,
      Map<LocalDate, String> activity,
      SortedSet<LocalDate> starts,
      SortedSet<LocalDate> ends) {
    NavigableMap<LocalDate, String> days = new TreeMap<>(activity);
    int gap = model.gapDays();
    SortedSet<LocalDate> found = new TreeSet<>();
    LocalDate lastActive = null;
    for (var day : days.entrySet()) {
      if (model.isActive(day.getValue())) {
        if (lastActive == null || ChronoUnit.DAYS.between(lastActive, day.getKey()) > gap) {
          found.add(day.getKey());
        }
        lastActive = day.getKey();
      }
    }
    for (LocalDate s : starts) {
      found.removeIf(c -> Math.abs(ChronoUnit.DAYS.between(c, s)) <= gap && !starts.contains(c));
      found.add(s);
    }
    List<LocalDate> ordered = new ArrayList<>();
    for (LocalDate s : found) {
      if (ordered.isEmpty() || ChronoUnit.DAYS.between(ordered.get(ordered.size() - 1), s) > gap) {
        ordered.add(s);
      }
    }

    List<Episode> out = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      LocalDate start = ordered.get(i);
      LocalDate next = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
      LocalDate activeEnd = activeEnd(model, days, ends, start, next);
      boolean complete = next != null;
      boolean untilEnd = model.span() == Span.UNTIL_END;
      LocalDate end = untilEnd ? activeEnd : next == null ? null : next.minusDays(1);
      LocalDate countUntil =
          untilEnd && activeEnd != null
              ? activeEnd.plusDays(1)
              : next == null ? LocalDate.MAX : next;

      Map<String, Integer> metrics = new LinkedHashMap<>();
      for (var metric : model.metrics()) {
        Integer value =
            switch (metric.kind()) {
              case LENGTH_DAYS -> {
                if (!complete) {
                  yield null;
                }
                if (!untilEnd) {
                  yield (int) ChronoUnit.DAYS.between(start, next);
                }
                yield activeEnd == null
                    ? null
                    : (int) ChronoUnit.DAYS.between(start, activeEnd) + 1;
              }
              case ACTIVE_DAYS ->
                  activeEnd == null ? null : (int) ChronoUnit.DAYS.between(start, activeEnd) + 1;
              case DAYS_WITH_VALUE -> {
                int n = 0;
                for (String v : days.subMap(start, true, countUntil, false).values()) {
                  if (v.equals(metric.value())) {
                    n++;
                  }
                }
                yield n;
              }
            };
        if (value != null) {
          metrics.put(metric.code(), value);
        }
      }
      out.add(new Episode(model.code(), start, end, activeEnd, complete, metrics));
    }
    return out;
  }

  private static LocalDate activeEnd(
      EpisodeModel model,
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
      } else if (model.isActive(value)) {
        last = day;
        unknown = 0;
      } else {
        break;
      }
      day = day.plusDays(1);
    }
    return last;
  }
}
