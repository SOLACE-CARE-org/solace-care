package care.solace.tracking.rules;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.context.ContextQuestion;
import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Synthetic histories as recipes (D61): the seeds rebuild the old ones and bad recipes stop. */
class SyntheticHistoriesTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

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
          "mood", element("mood", Kind.DAY_STATE, ValueType.INT, "{\"min\":1,\"max\":5}"),
          "sleep_minutes",
              element(
                  "sleep_minutes",
                  Kind.DAY_STATE,
                  ValueType.MINUTES,
                  "{\"min\":0,\"max\":1440,\"step\":15}"),
          "flow_intensity",
              element(
                  "flow_intensity",
                  Kind.DAY_STATE,
                  ValueType.ENUM,
                  "{\"values\":[\"none\",\"light\",\"medium\",\"heavy\",\"very_heavy\"]}"),
          "period_start", element("period_start", Kind.EVENT, ValueType.DATE, "{}"),
          "note", element("note", Kind.DAY_STATE, ValueType.TEXT, "{\"max\":4000}"));

  private static final Map<String, ContextQuestion> QUESTIONS =
      Map.of(
          "trying_to_conceive",
              new ContextQuestion(
                  "trying_to_conceive",
                  1,
                  ContextQuestion.Type.YES_NO,
                  ContextQuestion.Scope.PROFILE,
                  JSON.createObjectNode(),
                  List.of()),
          "hormonal_treatment",
              new ContextQuestion(
                  "hormonal_treatment",
                  1,
                  ContextQuestion.Type.YES_NO,
                  ContextQuestion.Scope.PROFILE,
                  JSON.createObjectNode(),
                  List.of()));

  private static List<String> problems(String recipe, String context) throws Exception {
    return SyntheticHistories.problems(
        JSON.readTree(recipe),
        context == null ? null : JSON.readTree(context),
        LIBRARY::get,
        QUESTIONS::get);
  }

  @Test
  void theSeedsAreTheTwelveHistoriesTheCodeBuilt() throws Exception {
    var seeds = SeededHistories.all();
    var legacy = LegacyHistories.all(TODAY);
    assertThat(seeds).extracting(SeededHistories.Seed::code).hasSize(12);
    for (var seed : seeds) {
      assertThat(
              SyntheticHistories.problems(
                  seed.recipe(), seed.context(), LIBRARY::get, QUESTIONS::get))
          .as(seed.code())
          .isEmpty();
      assertThat(seed.labels().path("en").asText()).isNotBlank();
      assertThat(seed.labels().path("fr").asText()).isNotBlank();
      var built = SyntheticHistories.build(seed.code(), seed.recipe(), seed.context(), TODAY);
      var old = legacy.stream().filter(h -> h.code().equals(seed.code())).findFirst().orElseThrow();
      assertThat(built.events()).as(seed.code()).isEqualTo(old.events());
      assertThat(built.answers()).as(seed.code()).isEqualTo(old.answers());
      if (seed.code().equals("sparse_logging")) {
        // Sleep is logged in steps of 15, so 200 minutes became 195 on the same days.
        assertThat(built.daily().get("mood")).isEqualTo(old.daily().get("mood"));
        assertThat(built.daily().get("sleep_minutes").keySet())
            .isEqualTo(old.daily().get("sleep_minutes").keySet());
        assertThat(built.daily().get("sleep_minutes").values()).containsOnly(195);
      } else {
        assertThat(built.daily()).as(seed.code()).isEqualTo(old.daily());
      }
    }
  }

  @Test
  void aRecipeOnlyLogsWhatAPhoneCould() throws Exception {
    String fine =
        "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"mood\",\"value\":2,\"days\":14}]}";
    assertThat(problems(fine, "{\"trying_to_conceive\":true}")).isEmpty();
    // Off the scale, an unknown element, notes, and an element logged twice.
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"mood\",\"value\":9,\"days\":14}]}",
                null))
        .containsExactly("recipe.parts[0]");
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"stress\",\"value\":2,\"days\":14}]}",
                null))
        .containsExactly("recipe.parts[0]");
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"note\",\"value\":\"x\",\"days\":3}]}",
                null))
        .containsExactly("recipe.parts[0]");
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"mood\",\"value\":2,\"days\":3},"
                    + "{\"pattern\":\"sparse\",\"element\":\"mood\",\"value\":2,\"days\":9,\"every\":3}]}",
                null))
        .containsExactly("recipe.parts[1]");
    // Episodes start with an event, and sleep comes in steps of 15.
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"episodes\",\"start\":\"flow_intensity\",\"lengths\":[28],\"currentDay\":2}]}",
                null))
        .containsExactly("recipe.parts[0]");
    assertThat(
            problems(
                "{\"parts\":[{\"pattern\":\"constant\",\"element\":\"sleep_minutes\",\"value\":200,\"days\":14}]}",
                null))
        .containsExactly("recipe.parts[0]");
    assertThat(problems("{\"parts\":[]}", null)).containsExactly("recipe");
    // Answers must fit a published question.
    assertThat(problems(fine, "{\"trying_to_conceive\":\"yes\"}"))
        .containsExactly("context.trying_to_conceive");
    assertThat(problems(fine, "{\"pcos_status\":true}")).containsExactly("context.pcos_status");
  }

  @Test
  void episodesWithoutActivityLogOnlyTheirStarts() throws Exception {
    JsonNode recipe =
        JSON.readTree(
            "{\"parts\":[{\"pattern\":\"episodes\",\"start\":\"period_start\",\"lengths\":[30,30],\"currentDay\":1}]}");
    var h = SyntheticHistories.build("starts", recipe, null, TODAY);
    assertThat(h.events().get("period_start"))
        .containsExactly(TODAY.minusDays(60), TODAY.minusDays(30), TODAY);
    assertThat(h.daily()).isEmpty();
  }
}
