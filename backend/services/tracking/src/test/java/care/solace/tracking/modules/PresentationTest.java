package care.solace.tracking.modules;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel;
import care.solace.tracking.episodes.TestModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A module's presentation and groups are checked against its bindings (D61). */
class PresentationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<EpisodeModel> MODELS = TestModels.launch().all();

  private static Element element(String code, Kind kind, ValueType type, List<String> signals)
      throws Exception {
    return new Element(
        UUID.randomUUID(),
        code,
        1,
        0,
        kind,
        type,
        JSON.readTree("{}"),
        "view",
        "icon",
        signals,
        JSON.readTree("{}"),
        "1.0.0");
  }

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  private static Map<String, Element> pcos() throws Exception {
    Map<String, Element> out = new LinkedHashMap<>();
    for (var e :
        List.of(
            element("flow_intensity", Kind.DAY_STATE, ValueType.ENUM, List.of("daily_value")),
            element("period_start", Kind.EVENT, ValueType.DATE, List.of()),
            element("period_end", Kind.EVENT, ValueType.DATE, List.of()),
            element("acne_severity", Kind.DAY_STATE, ValueType.ENUM, List.of("daily_value")),
            element("note", Kind.DAY_STATE, ValueType.TEXT, List.of()))) {
      out.put(e.code(), e);
    }
    return out;
  }

  @Test
  void theLaunchPresentationFitsItsModule() throws Exception {
    var presentation =
        json(
            """
            {"episode": {"model": "menstrual_cycle",
                         "dayLabel": {"en": "cycle day {{n}}", "fr": "jour {{n}} du cycle"},
                         "historyTitle": {"en": "Your last cycles", "fr": "Vos derniers cycles"}},
             "trends": ["menstrual_cycle.length_days", "daily_value:acne_severity"],
             "title": {"en": "Log today", "fr": "Noter aujourd'hui"},
             "quickLog": "acne_severity",
             "quickLogTitle": {"en": "How is your skin today?", "fr": "Comment va votre peau ?"}}
            """);
    assertThat(Presentation.problems(presentation, pcos(), MODELS)).isEmpty();
    assertThat(Presentation.problems(null, pcos(), MODELS)).isEmpty();
  }

  @Test
  void everyPartMustPointAtThisModule() throws Exception {
    var bound = pcos();
    assertThat(
            Presentation.problems(
                json("{\"title\": {\"en\": \"Log today\", \"fr\": \"\"}}"), bound, MODELS))
        .containsExactly("presentation.title");
    assertThat(
            Presentation.problems(
                json(
                    "{\"episode\": {\"model\": \"menstrual_cycle\","
                        + " \"dayLabel\": {\"en\": \"cycle day\", \"fr\": \"jour du cycle\"}}}"),
                bound,
                MODELS))
        .containsExactly("presentation.episode");
    assertThat(
            Presentation.problems(
                json("{\"trends\": [\"daily_value:mood\", \"daily_value:note\"]}"), bound, MODELS))
        .containsExactly("presentation.trends");
    assertThat(Presentation.problems(json("{\"quickLog\": \"note\"}"), bound, MODELS))
        .containsExactly("presentation.quickLog");
    assertThat(
            Presentation.problems(
                json("{\"quickLogTitle\": {\"en\": \"How are you?\"}}"), bound, MODELS))
        .containsExactly("presentation.quickLogTitle");
    // Without a bound start or activity element, the cycle model is not this module's.
    bound.remove("period_start");
    bound.remove("flow_intensity");
    assertThat(
            Presentation.problems(
                json(
                    "{\"episode\": {\"model\": \"menstrual_cycle\","
                        + " \"dayLabel\": {\"en\": \"day {{n}}\", \"fr\": \"jour {{n}}\"}}}"),
                bound,
                MODELS))
        .containsExactly("presentation.episode");
  }

  @Test
  void aGroupSitsTogetherAndSharesOneLabel() throws Exception {
    var label = json("{\"en\": \"Period dates\", \"fr\": \"Dates des règles\"}");
    var other = json("{\"en\": \"Dates\", \"fr\": \"Dates\"}");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    assertThat(
            Presentation.groupProblems(
                List.of(
                    new ModuleStore.Binding(a, true, 1),
                    new ModuleStore.Binding(b, true, 2, "period_dates", label),
                    new ModuleStore.Binding(c, true, 3, "period_dates", label))))
        .isEmpty();
    assertThat(
            Presentation.groupProblems(
                List.of(
                    new ModuleStore.Binding(b, true, 1, "period_dates", label),
                    new ModuleStore.Binding(a, true, 2),
                    new ModuleStore.Binding(c, true, 3, "period_dates", label))))
        .containsExactly("group:period_dates");
    assertThat(
            Presentation.groupProblems(
                List.of(
                    new ModuleStore.Binding(b, true, 1, "period_dates", label),
                    new ModuleStore.Binding(c, true, 2, "period_dates", other))))
        .containsExactly("group:period_dates");
  }
}
