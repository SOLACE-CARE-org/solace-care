package care.solace.tracking.episodes;

import java.time.LocalDate;
import java.util.Map;

/**
 * One episode an {@link EpisodeModel} found (Tracking Service Spec, Episode models). The newest is
 * never complete, so it never counts.
 *
 * @param end the day before the next start, or the end of the run; null while it is open
 * @param activeEnd the last day of the active run; null when nothing shows where it ends
 * @param metrics the model's metrics by code; a metric is absent while it is unknown
 */
public record Episode(
    String model,
    LocalDate start,
    LocalDate end,
    LocalDate activeEnd,
    boolean complete,
    Map<String, Integer> metrics) {

  public Integer metric(String code) {
    return metrics.get(code);
  }
}
