package care.solace.tracking.episodes;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel.Metric;
import care.solace.tracking.episodes.EpisodeModel.MetricKind;
import care.solace.tracking.episodes.EpisodeModel.Span;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What an episode model draft must be before review (Tracking Service Spec, Episode models). */
class EpisodeModelRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Element element(String code, Kind kind, ValueType type, String constraints) {
    try {
      return new Element(
          UUID.randomUUID(),
          code,
          1,
          0,
          kind,
          type,
          JSON.readTree(constraints),
          "view",
          "icon",
          List.of(),
          JSON.readTree("{}"),
          "1.0.0");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final Map<String, Element> LIBRARY =
      Map.of(
          "period_start", element("period_start", Kind.EVENT, ValueType.DATE, "{}"),
          "period_end", element("period_end", Kind.EVENT, ValueType.DATE, "{}"),
          "flow_intensity",
              element(
                  "flow_intensity",
                  Kind.DAY_STATE,
                  ValueType.ENUM,
                  "{\"values\":[\"none\",\"light\",\"heavy\"]}"),
          "mood", element("mood", Kind.DAY_STATE, ValueType.INT, "{\"min\":1,\"max\":5}"));

  private static JsonNode labels(String en, String fr) {
    return JSON.createObjectNode().put("en", en).put("fr", fr);
  }

  private static Metric metric(String code, MetricKind kind, String value) {
    return new Metric(code, kind, value, labels("Days", "Jours"));
  }

  private static EpisodeModel model(
      String start, String end, String activity, Set<String> active, Span span, List<Metric> m) {
    return new EpisodeModel(
        "cycle", 0, start, end, activity, active, 10, span, m, labels("Cycle", "Cycle"));
  }

  private static List<String> problems(EpisodeModel m, EpisodeModel published) {
    return EpisodeModelRules.problems(m, c -> Optional.ofNullable(LIBRARY.get(c)), published);
  }

  private static final List<Metric> ALL =
      List.of(
          metric("length_days", MetricKind.LENGTH_DAYS, null),
          metric("period_days", MetricKind.ACTIVE_DAYS, null),
          metric("heavy_days", MetricKind.DAYS_WITH_VALUE, "heavy"));

  @Test
  void theLaunchShapeIsReady() {
    var m =
        model(
            "period_start",
            "period_end",
            "flow_intensity",
            new LinkedHashSet<>(List.of("light", "heavy")),
            Span.UNTIL_NEXT_START,
            ALL);
    assertThat(problems(m, null)).isEmpty();
  }

  @Test
  void elementsMustBeTheRightKindAndValuesTheirOwn() {
    var m =
        model(
            "mood",
            "period_start",
            "mood",
            Set.of("light"),
            Span.UNTIL_NEXT_START,
            List.of(metric("length_days", MetricKind.LENGTH_DAYS, null)));
    assertThat(problems(m, null)).containsExactly("start", "activity", "activeValues");
    var unknownValue =
        model(
            "period_start",
            null,
            "flow_intensity",
            Set.of("spotting"),
            Span.UNTIL_NEXT_START,
            List.of(metric("x_days", MetricKind.DAYS_WITH_VALUE, "spotting")));
    assertThat(problems(unknownValue, null)).containsExactly("activeValues", "metrics");
    var sameStartAndEnd =
        model(
            "period_start",
            "period_start",
            null,
            Set.of(),
            Span.UNTIL_END,
            List.of(metric("length_days", MetricKind.LENGTH_DAYS, null)));
    assertThat(problems(sameStartAndEnd, null)).containsExactly("end");
  }

  @Test
  void anEpisodeThatLastsUntilItsEndNeedsSomethingToEndIt() {
    var m =
        model(
            "period_start",
            null,
            null,
            Set.of(),
            Span.UNTIL_END,
            List.of(metric("length_days", MetricKind.LENGTH_DAYS, null)));
    assertThat(problems(m, null)).containsExactly("span");
  }

  @Test
  void metricsNeedTheActivityElementUniqueCodesAndKeepWhatWasPublished() {
    var noActivity =
        model(
            "period_start",
            null,
            null,
            Set.of(),
            Span.UNTIL_NEXT_START,
            List.of(metric("period_days", MetricKind.ACTIVE_DAYS, null)));
    assertThat(problems(noActivity, null)).containsExactly("metrics");
    var twice =
        model(
            "period_start",
            null,
            null,
            Set.of(),
            Span.UNTIL_NEXT_START,
            List.of(
                metric("length_days", MetricKind.LENGTH_DAYS, null),
                metric("length_days", MetricKind.LENGTH_DAYS, null)));
    assertThat(problems(twice, null)).containsExactly("metrics");
    var none = model("period_start", null, null, Set.of(), Span.UNTIL_NEXT_START, List.of());
    assertThat(problems(none, null)).containsExactly("metrics");

    var published =
        model(
            "period_start",
            "period_end",
            "flow_intensity",
            Set.of("light", "heavy"),
            Span.UNTIL_NEXT_START,
            ALL);
    var dropsOne =
        model(
            "period_start",
            "period_end",
            "flow_intensity",
            Set.of("light", "heavy"),
            Span.UNTIL_NEXT_START,
            ALL.subList(0, 2));
    assertThat(problems(dropsOne, published)).containsExactly("metrics");
  }

  @Test
  void everyNameIsInBothLanguages() {
    var m =
        new EpisodeModel(
            "cycle",
            0,
            "period_start",
            null,
            null,
            Set.of(),
            10,
            Span.UNTIL_NEXT_START,
            List.of(new Metric("length_days", MetricKind.LENGTH_DAYS, null, labels("Length", " "))),
            labels("Cycle", "Cycle"));
    assertThat(problems(m, null)).containsExactly("labels");
  }
}
