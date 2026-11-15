package care.solace.tracking.episodes;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel.Metric;
import care.solace.tracking.episodes.EpisodeModel.MetricKind;
import care.solace.tracking.episodes.EpisodeModel.Span;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What an episode model draft must be before review (Tracking Service Spec, Episode models; D61).
 * Pure, so the builder and the tests share it.
 */
public final class EpisodeModelRules {

  public static final int MAX_METRICS = 8;
  public static final int MAX_NAME = 60;

  private static final String CODE = "^[a-z][a-z0-9_]*$";

  private EpisodeModelRules() {}

  /**
   * What stops a draft being submitted; empty when it is ready.
   *
   * <ul>
   *   <li>{@code start}: not a published EVENT element
   *   <li>{@code end}: set, but not a published EVENT element, or the same as the start
   *   <li>{@code activity}: set, but not a published DAY_STATE element with named values
   *   <li>{@code activeValues}: an activity element with no active value, or a value it lacks
   *   <li>{@code span}: until the end, with neither an end nor an activity element to end it
   *   <li>{@code metrics}: none, too many, a bad or repeated code, a kind that needs the activity
   *       element without one, a counted value the activity element lacks, or a metric the
   *       published version has that this one drops (rules read metrics by code)
   *   <li>{@code labels}: a model or metric name missing in EN or FR, or too long
   * </ul>
   *
   * @param elements the published element of a code
   * @param published the newest published version of the same code, or null
   */
  public static List<String> problems(
      EpisodeModel d, Function<String, Optional<Element>> elements, EpisodeModel published) {
    List<String> problems = new ArrayList<>();
    if (!event(elements.apply(d.startElement()))) {
      problems.add("start");
    }
    if (d.endElement() != null
        && (!event(elements.apply(d.endElement())) || d.endElement().equals(d.startElement()))) {
      problems.add("end");
    }
    Optional<Element> activity =
        d.activityElement() == null ? Optional.empty() : elements.apply(d.activityElement());
    Set<String> values = activity.map(EpisodeModelRules::values).orElse(Set.of());
    if (d.activityElement() != null && !activityFine(activity)) {
      problems.add("activity");
    }
    if (d.activityElement() != null
        && (d.activeValues().isEmpty() || !values.containsAll(d.activeValues()))) {
      problems.add("activeValues");
    }
    if (d.span() == Span.UNTIL_END && d.endElement() == null && d.activityElement() == null) {
      problems.add("span");
    }
    if (!metricsFine(d, values, published)) {
      problems.add("metrics");
    }
    if (!labelsFine(d)) {
      problems.add("labels");
    }
    return problems;
  }

  private static boolean event(Optional<Element> e) {
    return e.map(x -> x.published() && x.kind() == Kind.EVENT).orElse(false);
  }

  private static boolean activityFine(Optional<Element> e) {
    return e.map(
            x ->
                x.published()
                    && x.kind() == Kind.DAY_STATE
                    && x.valueType() == ValueType.ENUM
                    && !values(x).isEmpty())
        .orElse(false);
  }

  private static Set<String> values(Element e) {
    Set<String> out = new HashSet<>();
    e.constraints().path("values").forEach(v -> out.add(v.asText()));
    return out;
  }

  private static boolean metricsFine(EpisodeModel d, Set<String> values, EpisodeModel published) {
    if (d.metrics().isEmpty() || d.metrics().size() > MAX_METRICS) {
      return false;
    }
    Set<String> codes = new HashSet<>();
    for (Metric m : d.metrics()) {
      if (m.code() == null || !m.code().matches(CODE) || m.code().length() > 40) {
        return false;
      }
      if (!codes.add(m.code())) {
        return false;
      }
      if (m.kind() != MetricKind.LENGTH_DAYS && d.activityElement() == null) {
        return false;
      }
      if (m.kind() == MetricKind.DAYS_WITH_VALUE
          && (m.value() == null || !values.contains(m.value()))) {
        return false;
      }
    }
    if (published != null) {
      for (Metric m : published.metrics()) {
        if (!codes.contains(m.code())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean labelsFine(EpisodeModel d) {
    if (!named(d.labels())) {
      return false;
    }
    return d.metrics().stream().allMatch(m -> named(m.labels()));
  }

  private static boolean named(JsonNode labels) {
    if (labels == null) {
      return false;
    }
    for (String lang : List.of("en", "fr")) {
      String text = labels.path(lang).asText("").trim();
      if (text.isEmpty() || text.length() > MAX_NAME) {
        return false;
      }
    }
    return true;
  }
}
