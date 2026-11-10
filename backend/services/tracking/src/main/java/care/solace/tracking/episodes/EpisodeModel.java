package care.solace.tracking.episodes;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How episodes are found in what a person logged, and what each complete one measures (Tracking
 * Service Spec, Episode models; D61). A reviewed definition, not code: the algorithm in {@link
 * Episodes} reads it.
 *
 * @param startElement an EVENT element that marks a start
 * @param endElement an optional EVENT element that marks an end
 * @param activityElement an optional DAY_STATE element whose active days make up the run
 * @param activeValues which values of the activity element count as active
 * @param gapDays days without activity after which the next active day starts a new episode
 * @param labels {"en": "...", "fr": "..."}
 */
public record EpisodeModel(
    String code,
    int version,
    String startElement,
    String endElement,
    String activityElement,
    Set<String> activeValues,
    int gapDays,
    Span span,
    List<Metric> metrics,
    JsonNode labels) {

  public enum Span {
    /** An episode lasts until the next one starts, as cycles do. */
    UNTIL_NEXT_START,
    /** An episode lasts until its active run ends, as an attack does. */
    UNTIL_END
  }

  /** The fixed list of measures an episode model can produce. */
  public enum MetricKind {
    /** Days from the start to the episode's end (to the next start, or the end of the run). */
    LENGTH_DAYS,
    /** Days from the start to the end of the active run. */
    ACTIVE_DAYS,
    /** Days in the episode logged with one named value of the activity element. */
    DAYS_WITH_VALUE
  }

  /**
   * One number per complete episode, read by rules as the signal {@code <model>.<code>}.
   *
   * @param value the activity value counted, for DAYS_WITH_VALUE only
   * @param labels {"en": "...", "fr": "..."}
   */
  public record Metric(String code, MetricKind kind, String value, JsonNode labels) {}

  /** The elements this model reads, start first. */
  public Set<String> elements() {
    Set<String> out = new LinkedHashSet<>();
    out.add(startElement);
    if (endElement != null) {
      out.add(endElement);
    }
    if (activityElement != null) {
      out.add(activityElement);
    }
    return out;
  }

  /** The signal code rules use for one of this model's metrics. */
  public String signal(Metric metric) {
    return code + "." + metric.code();
  }

  public Optional<Metric> metric(String code) {
    return metrics.stream().filter(m -> m.code().equals(code)).findFirst();
  }

  public boolean isActive(String value) {
    return value != null && activeValues.contains(value);
  }
}
