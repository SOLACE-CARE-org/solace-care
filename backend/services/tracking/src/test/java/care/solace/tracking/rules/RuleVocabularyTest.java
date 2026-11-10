package care.solace.tracking.rules;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel;
import care.solace.tracking.episodes.TestModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Signals come from the bound elements and episode models, operators from the registry, so the
 * builder's vocabulary and the validator agree, with no element code named in either (D61).
 */
class RuleVocabularyTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<EpisodeModel> MODELS = TestModels.launch().all();

  private static Element element(
      String code, Kind kind, ValueType type, String constraints, List<String> signals)
      throws Exception {
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
        signals,
        JSON.readTree("{}"),
        "1.0.0");
  }

  private static Map<String, Element> bound(Element... elements) {
    Map<String, Element> out = new LinkedHashMap<>();
    for (Element e : elements) {
      out.put(e.code(), e);
    }
    return out;
  }

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  @Test
  void dailySignalsForReadableElementsAndTheSignalsOfModelsThatReadThem() throws Exception {
    var flow =
        element(
            "flow_intensity",
            Kind.DAY_STATE,
            ValueType.ENUM,
            "{\"values\":[\"none\",\"light\",\"very_heavy\"]}",
            List.of("daily_value"));
    var start = element("period_start", Kind.EVENT, ValueType.DATE, "{}", List.of());
    var note = element("note", Kind.DAY_STATE, ValueType.TEXT, "{}", List.of());
    var signals = SignalCatalog.forModule(bound(flow, start, note), MODELS);
    assertThat(signals)
        .extracting(SignalCatalog.Signal::key)
        .containsExactly(
            "daily_value:flow_intensity",
            "menstrual_cycle.length_days",
            "menstrual_cycle.period_days",
            "menstrual_cycle.very_heavy_days");
    assertThat(signals.get(0).values()).containsExactly("none", "light", "very_heavy");
    assertThat(signals.get(1).kind()).isEqualTo(SignalCatalog.Kind.EPISODE);
    assertThat(signals.get(1).labels().path("fr").asText()).isEqualTo("Durée du cycle");
  }

  @Test
  void episodeSignalsComeFromTheModelsNotFromAFixedCode() throws Exception {
    var headache =
        element("headache", Kind.DAY_STATE, ValueType.BOOL, "{}", List.of("daily_value"));
    var conditions =
        json(
            "[{\"signal\":\"migraine.length_days\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gte\",\"threshold\":2,\"count\":3}]");
    var both = List.of(TestModels.menstrualCycle(), TestModels.migraine());
    assertThat(RuleRules.problems(conditions, json("[]"), bound(headache), both)).isEmpty();
    assertThat(RuleRules.problems(conditions, json("[]"), bound(headache), MODELS))
        .contains("signal not offered by this module: migraine.length_days");
    assertThat(SignalCatalog.forModule(bound(headache), both))
        .extracting(SignalCatalog.Signal::key)
        .doesNotContain("menstrual_cycle.length_days");
  }

  @Test
  void consecutiveEpisodesReadsOnlyEpisodeSignals() throws Exception {
    var mood =
        element(
            "mood", Kind.DAY_STATE, ValueType.INT, "{\"min\":1,\"max\":5}", List.of("daily_value"));
    var conditions =
        json(
            "[{\"signal\":\"daily_value\",\"element\":\"mood\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gt\",\"threshold\":3,\"count\":2}]");
    assertThat(RuleRules.problems(conditions, json("[]"), bound(mood), MODELS))
        .contains("operator consecutive_episodes cannot read daily_value:mood");
  }

  @Test
  void operatorsAndComparisonsFollowTheSignal() throws Exception {
    var mood =
        element(
            "mood", Kind.DAY_STATE, ValueType.INT, "{\"min\":1,\"max\":5}", List.of("daily_value"));
    var stress =
        element(
            "stress_level",
            Kind.DAY_STATE,
            ValueType.ENUM,
            "{\"values\":[\"low\",\"high\"]}",
            List.of("daily_value"));
    var signals = SignalCatalog.forModule(bound(mood, stress), MODELS);
    var moodSignal = signals.get(0);
    var stressSignal = signals.get(1);
    assertThat(Operators.all().stream().filter(o -> o.accepts(moodSignal)))
        .extracting(Operator::code)
        .containsExactly("days_in_window", "average_in_window");
    assertThat(Operators.all().stream().filter(o -> o.accepts(stressSignal)))
        .extracting(Operator::code)
        .containsExactly("days_in_window");
    assertThat(Operators.find("days_in_window").orElseThrow().comparisons(stressSignal))
        .containsExactly("eq", "in");

    var averageOfStress =
        json(
            "[{\"signal\":\"daily_value\",\"element\":\"stress_level\",\"operator\":\"average_in_window\",\"comparison\":\"gt\",\"threshold\":1,\"window\":14,\"minData\":7}]");
    assertThat(RuleRules.problems(averageOfStress, json("[]"), bound(mood, stress), MODELS))
        .contains("operator average_in_window cannot read daily_value:stress_level");
    var greaterThanStress =
        json(
            "[{\"signal\":\"daily_value\",\"element\":\"stress_level\",\"operator\":\"days_in_window\",\"comparison\":\"gt\",\"threshold\":1,\"mode\":\"at_least\",\"count\":3,\"window\":14,\"minData\":7}]");
    assertThat(RuleRules.problems(greaterThanStress, json("[]"), bound(mood, stress), MODELS))
        .contains("comparison gt cannot be used here");
  }

  @Test
  void theLaunchRulesStillValidateAgainstTheLaunchElements() throws Exception {
    var flow =
        element(
            "flow_intensity",
            Kind.DAY_STATE,
            ValueType.ENUM,
            "{\"values\":[\"none\",\"light\",\"medium\",\"heavy\",\"very_heavy\"]}",
            List.of("daily_value"));
    var start = element("period_start", Kind.EVENT, ValueType.DATE, "{}", List.of());
    var mood = element("mood", Kind.DAY_STATE, ValueType.INT, "{}", List.of("daily_value"));
    var sleep =
        element("sleep_minutes", Kind.DAY_STATE, ValueType.MINUTES, "{}", List.of("daily_value"));
    var all = bound(flow, start, mood, sleep);
    for (var rule : LaunchRulesTest.CONDITIONS.entrySet()) {
      assertThat(RuleRules.problems(json(rule.getValue()), json("[]"), all, MODELS))
          .as(rule.getKey())
          .isEmpty();
    }
  }

  @Test
  void laterConditionsFillNumberedPlaceholders() throws Exception {
    LocalDate today = LocalDate.of(2026, 11, 10);
    NavigableMap<LocalDate, Object> mood = new TreeMap<>();
    NavigableMap<LocalDate, Object> sleep = new TreeMap<>();
    for (int i = 0; i < 14; i++) {
      mood.put(today.minusDays(i), 2);
      sleep.put(today.minusDays(i), 240);
    }
    var signals =
        new RuleEngine.Signals(today, Map.of("mood", mood, "sleep_minutes", sleep), Map.of());
    var result =
        RuleEngine.evaluate(
            json(
                """
                [{"signal":"daily_value","element":"mood","operator":"days_in_window","comparison":"lte",
                  "threshold":2,"mode":"at_least","count":10,"window":14,"minData":10},
                 {"signal":"daily_value","element":"sleep_minutes","operator":"average_in_window",
                  "comparison":"lt","threshold":300,"window":14,"minData":10}]\
                """),
            signals);
    assertThat(result.held()).isTrue();
    String message =
        RuleRules.render(
            "Low mood on {n} of {window} days, and {c2.average} minutes of sleep on average.",
            result.evidence());
    assertThat(message)
        .isEqualTo("Low mood on 14 of 14 days, and 240.0 minutes of sleep on average.");
  }

  @Test
  void aSingleConditionKeepsItsEvidenceAsBefore() throws Exception {
    LocalDate today = LocalDate.of(2026, 11, 10);
    NavigableMap<LocalDate, Object> mood = new TreeMap<>();
    mood.put(today, 1);
    var result =
        RuleEngine.evaluate(
            json(
                "[{\"signal\":\"daily_value\",\"element\":\"mood\",\"operator\":\"days_in_window\",\"comparison\":\"lte\",\"threshold\":2,\"mode\":\"at_least\",\"count\":1,\"window\":14,\"minData\":1}]"),
            new RuleEngine.Signals(today, Map.of("mood", mood), Map.of()));
    assertThat(result.evidence()).doesNotContainKey("c1.n").containsEntry("n", 1);
  }
}
