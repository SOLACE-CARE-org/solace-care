package care.solace.tracking.context;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.context.ContextQuestion.Option;
import care.solace.tracking.context.ContextQuestion.Scope;
import care.solace.tracking.context.ContextQuestion.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a context question draft must be before review (Tracking Service Spec, Context questions).
 */
class ContextQuestionRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode question(String en, String fr) {
    var labels = JSON.createObjectNode();
    labels.putObject("en").put("question", en);
    labels.putObject("fr").put("question", fr);
    return labels;
  }

  private static Option option(String code, String en, String fr) {
    return new Option(code, JSON.createObjectNode().put("en", en).put("fr", fr));
  }

  private static final List<Option> METHODS =
      List.of(option("pill", "Pill", "Pilule"), option("coil", "Coil", "Stérilet"));

  private static ContextQuestion q(Type type, List<Option> options) {
    return new ContextQuestion(
        "contraception",
        0,
        type,
        Scope.ENROLMENT,
        question("Which contraception do you use?", "Quelle contraception utilisez-vous ?"),
        options);
  }

  @Test
  void aChoiceQuestionWithTwoNamedOptionsIsReady() {
    assertThat(ContextQuestionRules.problems(q(Type.ONE_CHOICE, METHODS), null)).isEmpty();
    assertThat(ContextQuestionRules.problems(q(Type.MANY_CHOICES, METHODS), null)).isEmpty();
    assertThat(ContextQuestionRules.problems(q(Type.YES_NO, List.of()), null)).isEmpty();
  }

  @Test
  void optionsFitTheType() {
    assertThat(ContextQuestionRules.problems(q(Type.YES_NO, METHODS), null))
        .containsExactly("options");
    assertThat(ContextQuestionRules.problems(q(Type.ONE_CHOICE, METHODS.subList(0, 1)), null))
        .containsExactly("options");
    var twice = List.of(option("pill", "Pill", "Pilule"), option("pill", "Patch", "Patch"));
    assertThat(ContextQuestionRules.problems(q(Type.ONE_CHOICE, twice), null))
        .containsExactly("options");
    var noFrench = List.of(option("pill", "Pill", "Pilule"), option("coil", "Coil", " "));
    assertThat(ContextQuestionRules.problems(q(Type.MANY_CHOICES, noFrench), null))
        .containsExactly("options");
  }

  @Test
  void theQuestionIsInBothLanguages() {
    var m =
        new ContextQuestion(
            "contraception",
            0,
            Type.YES_NO,
            Scope.PROFILE,
            question("Do you use contraception?", ""),
            List.of());
    assertThat(ContextQuestionRules.problems(m, null)).containsExactly("question");
  }

  @Test
  void aNewVersionKeepsTheTypeAndEveryOption() {
    var published = q(Type.ONE_CHOICE, METHODS);
    var more =
        q(
            Type.ONE_CHOICE,
            List.of(METHODS.get(0), METHODS.get(1), option("none", "None", "Aucune")));
    assertThat(ContextQuestionRules.problems(more, published)).isEmpty();
    var dropsOne = q(Type.ONE_CHOICE, List.of(METHODS.get(0), option("none", "None", "Aucune")));
    assertThat(ContextQuestionRules.problems(dropsOne, published)).containsExactly("options");
    assertThat(ContextQuestionRules.problems(q(Type.MANY_CHOICES, METHODS), published))
        .containsExactly("type");
  }
}
