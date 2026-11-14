package care.solace.tracking.elements;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.elements.ElementRules.Draft;
import care.solace.tracking.elements.InputViews.InputView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What an element draft must be before review, and how new versions are numbered (D61). */
class ElementRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The registry as V1_12 seeds it. */
  static final InputViews VIEWS =
      new InputViews(
          List.of(
              view("scale_5", ValueType.INT, Kind.DAY_STATE, "{}"),
              view(
                  "duration_stepper",
                  ValueType.MINUTES,
                  Kind.DAY_STATE,
                  "{\"start\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1440}}"),
              view("choice_chips", ValueType.ENUM, Kind.DAY_STATE, "{}"),
              view("toggle", ValueType.BOOL, Kind.DAY_STATE, "{}"),
              view("flow_calendar", ValueType.ENUM, Kind.DAY_STATE, "{}"),
              view("event_marker", ValueType.DATE, Kind.EVENT, "{}"),
              view("free_text", ValueType.TEXT, Kind.DAY_STATE, "{}")));

  private static InputView view(String key, ValueType type, Kind kind, String props) {
    return new InputView(key, List.of(type), List.of(kind), read(props), "1.0.0");
  }

  private static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static List<String> problems(Draft d) {
    return ElementRules.problems(d, VIEWS.find(d.inputView()).orElse(null));
  }

  private static Draft draft(
      String code,
      Kind kind,
      ValueType type,
      String constraints,
      String view,
      String props,
      String icon,
      String labels) {
    return new Draft(code, kind, type, read(constraints), view, read(props), icon, read(labels));
  }

  private static Draft headache(String values, String labels) {
    return draft(
        "headache",
        Kind.DAY_STATE,
        ValueType.ENUM,
        "{\"values\":" + values + "}",
        "choice_chips",
        "{}",
        "brain",
        labels);
  }

  private static final String HEADACHE_LABELS =
      "{\"en\":{\"name\":\"Headache\",\"values\":{\"none\":\"None\",\"some\":\"Some\",\"bad\":\"Bad\"}},"
          + "\"fr\":{\"name\":\"Mal de tête\",\"values\":{\"none\":\"Aucun\",\"some\":\"Un peu\",\"bad\":\"Fort\"}}}";

  @Test
  void theLaunchCatalogueMeetsTheRulesItWouldBeDraftedUnder() {
    var launch =
        List.of(
            draft(
                "mood",
                Kind.DAY_STATE,
                ValueType.INT,
                "{\"min\":1,\"max\":5}",
                "scale_5",
                "{}",
                "mood",
                "{\"en\":{\"name\":\"Mood\",\"values\":{\"1\":\"Awful\",\"2\":\"Low\",\"3\":\"Okay\",\"4\":\"Good\",\"5\":\"Great\"}},"
                    + "\"fr\":{\"name\":\"Humeur\",\"values\":{\"1\":\"Très mal\",\"2\":\"Pas bien\",\"3\":\"Moyen\",\"4\":\"Bien\",\"5\":\"Très bien\"}}}"),
            draft(
                "sleep_minutes",
                Kind.DAY_STATE,
                ValueType.MINUTES,
                "{\"min\":0,\"max\":1440,\"step\":15}",
                "duration_stepper",
                "{\"start\":420}",
                "moon",
                "{\"en\":{\"name\":\"Sleep last night\"},\"fr\":{\"name\":\"Sommeil cette nuit\"}}"),
            draft(
                "note",
                Kind.DAY_STATE,
                ValueType.TEXT,
                "{\"max\":4000}",
                "free_text",
                "{}",
                "pen",
                "{\"en\":{\"name\":\"Note\",\"help\":\"Private.\"},\"fr\":{\"name\":\"Note\",\"help\":\"Privée.\"}}"),
            draft(
                "period_start",
                Kind.EVENT,
                ValueType.DATE,
                "{}",
                "event_marker",
                "{}",
                "calendar-start",
                "{\"en\":{\"name\":\"Period started\",\"help\":\"Started today\"},"
                    + "\"fr\":{\"name\":\"Début des règles\",\"help\":\"Début aujourd'hui\"}}"),
            draft(
                "excess_hair",
                Kind.DAY_STATE,
                ValueType.BOOL,
                "{}",
                "toggle",
                "{}",
                "hair",
                "{\"en\":{\"name\":\"Excess hair growth\"},\"fr\":{\"name\":\"Pilosité excessive\"}}"),
            headache("[\"none\",\"some\",\"bad\"]", HEADACHE_LABELS));
    for (var d : launch) {
      assertThat(problems(d)).as(d.code()).isEmpty();
    }
  }

  @Test
  void theViewMustAcceptTheValueTypeAndKind() {
    var d = headache("[\"none\",\"some\",\"bad\"]", HEADACHE_LABELS);
    assertThat(problems(withView(d, "toggle"))).containsExactly("inputView");
    assertThat(problems(withView(d, "slider"))).containsExactly("inputView");
    var marker =
        draft(
            "pain_day",
            Kind.DAY_STATE,
            ValueType.DATE,
            "{}",
            "event_marker",
            "{}",
            "bolt",
            "{\"en\":{\"name\":\"Pain\"},\"fr\":{\"name\":\"Douleur\"}}");
    assertThat(problems(marker)).containsExactly("inputView");
  }

  @Test
  void everyValueNeedsALabelInBothLanguages() {
    String missingFr =
        "{\"en\":{\"name\":\"Headache\",\"values\":{\"none\":\"None\",\"some\":\"Some\",\"bad\":\"Bad\"}},"
            + "\"fr\":{\"name\":\"Mal de tête\",\"values\":{\"none\":\"Aucun\",\"some\":\"Un peu\"}}}";
    assertThat(problems(headache("[\"none\",\"some\",\"bad\"]", missingFr)))
        .containsExactly("values");
    String noFrName =
        "{\"en\":{\"name\":\"Headache\",\"values\":{\"none\":\"None\",\"some\":\"Some\",\"bad\":\"Bad\"}},"
            + "\"fr\":{\"name\":\" \",\"values\":{\"none\":\"Aucun\",\"some\":\"Un peu\",\"bad\":\"Fort\"}}}";
    assertThat(problems(headache("[\"none\",\"some\",\"bad\"]", noFrName)))
        .containsExactly("labels");
    String helpInOne =
        "{\"en\":{\"name\":\"Headache\",\"help\":\"Today\",\"values\":{\"none\":\"None\",\"some\":\"Some\",\"bad\":\"Bad\"}},"
            + "\"fr\":{\"name\":\"Mal de tête\",\"values\":{\"none\":\"Aucun\",\"some\":\"Un peu\",\"bad\":\"Fort\"}}}";
    assertThat(problems(headache("[\"none\",\"some\",\"bad\"]", helpInOne)))
        .containsExactly("labels");
  }

  @Test
  void constraintsFitTheValueType() {
    assertThat(problems(headache("[\"none\"]", HEADACHE_LABELS))).contains("constraints");
    assertThat(problems(headache("[\"none\",\"none\",\"bad\"]", HEADACHE_LABELS)))
        .contains("constraints");
    assertThat(problems(headache("[\"None\",\"some\",\"bad\"]", HEADACHE_LABELS)))
        .contains("constraints");
    // A five-point scale has exactly five points.
    var scale =
        draft(
            "energy",
            Kind.DAY_STATE,
            ValueType.INT,
            "{\"min\":1,\"max\":4}",
            "scale_5",
            "{}",
            "sun",
            "{\"en\":{\"name\":\"Energy\",\"values\":{\"1\":\"a\",\"2\":\"b\",\"3\":\"c\",\"4\":\"d\"}},"
                + "\"fr\":{\"name\":\"Énergie\",\"values\":{\"1\":\"a\",\"2\":\"b\",\"3\":\"c\",\"4\":\"d\"}}}");
    assertThat(problems(scale)).containsExactly("constraints");
    // Minutes in steps the bounds fall on.
    assertThat(
            ElementRules.constraintsFine(
                ValueType.MINUTES, read("{\"min\":0,\"max\":1000,\"step\":15}")))
        .isFalse();
    assertThat(ElementRules.constraintsFine(ValueType.BOOL, read("{\"max\":1}"))).isFalse();
  }

  @Test
  void viewPropsMustBeOnesTheViewTakesAndFitTheConstraints() {
    var sleep =
        draft(
            "nap_minutes",
            Kind.DAY_STATE,
            ValueType.MINUTES,
            "{\"min\":0,\"max\":240,\"step\":15}",
            "duration_stepper",
            "{\"start\":30}",
            "moon",
            "{\"en\":{\"name\":\"Nap\"},\"fr\":{\"name\":\"Sieste\"}}");
    assertThat(problems(sleep)).isEmpty();
    assertThat(problems(withProps(sleep, "{\"start\":300}"))).containsExactly("viewProps");
    assertThat(problems(withProps(sleep, "{\"start\":20}"))).containsExactly("viewProps");
    assertThat(problems(withProps(sleep, "{\"colour\":\"red\"}"))).containsExactly("viewProps");
    assertThat(problems(withProps(sleep, "{\"start\":\"30\"}"))).containsExactly("viewProps");
  }

  @Test
  void theIconComesFromTheAppsSet() {
    var d = headache("[\"none\",\"some\",\"bad\"]", HEADACHE_LABELS);
    assertThat(
            problems(
                new Draft(
                    d.code(),
                    d.kind(),
                    d.valueType(),
                    d.constraints(),
                    d.inputView(),
                    d.viewProps(),
                    "rocket",
                    d.labels())))
        .containsExactly("icon");
  }

  @Test
  void addingAValueIsMinorAndChangingOrRemovingOneIsMajor() {
    Element v1 =
        new Element(
            UUID.randomUUID(),
            "headache",
            1,
            0,
            Kind.DAY_STATE,
            ValueType.ENUM,
            read("{\"values\":[\"none\",\"some\",\"bad\"]}"),
            "choice_chips",
            "brain",
            List.of("daily_value"),
            read(HEADACHE_LABELS),
            "1.0.0");
    assertThat(
            ElementRules.number(
                headache("[\"none\",\"some\",\"bad\",\"worst\"]", HEADACHE_LABELS), v1))
        .containsExactly(1, 1);
    assertThat(ElementRules.number(headache("[\"none\",\"some\",\"bad\"]", HEADACHE_LABELS), v1))
        .containsExactly(1, 1);
    assertThat(ElementRules.number(headache("[\"none\",\"bad\"]", HEADACHE_LABELS), v1))
        .containsExactly(2, 0);
    assertThat(ElementRules.number(headache("[\"none\",\"mild\",\"bad\"]", HEADACHE_LABELS), v1))
        .containsExactly(2, 0);
    var toggle =
        new Draft(
            "headache",
            Kind.DAY_STATE,
            ValueType.BOOL,
            read("{}"),
            "toggle",
            read("{}"),
            "brain",
            read(HEADACHE_LABELS));
    assertThat(ElementRules.number(toggle, v1)).containsExactly(2, 0);
  }

  @Test
  void signalsAreDerived() {
    assertThat(ElementRules.signals(ValueType.ENUM)).containsExactly("daily_value");
    assertThat(ElementRules.signals(ValueType.TEXT)).isEmpty();
  }

  private static Draft withView(Draft d, String view) {
    return new Draft(
        d.code(),
        d.kind(),
        d.valueType(),
        d.constraints(),
        view,
        d.viewProps(),
        d.icon(),
        d.labels());
  }

  private static Draft withProps(Draft d, String props) {
    return new Draft(
        d.code(),
        d.kind(),
        d.valueType(),
        d.constraints(),
        d.inputView(),
        read(props),
        d.icon(),
        d.labels());
  }
}
