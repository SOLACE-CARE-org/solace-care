package care.solace.tracking.rules;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.context.ContextQuestions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A rule's "unless" reads context answers by question code and only ever switches rules off. */
class UnlessTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(String s) throws Exception {
    return JSON.readTree(s);
  }

  private static final List<care.solace.tracking.context.ContextQuestion> QUESTIONS;

  static {
    try {
      QUESTIONS =
          List.of(
              ContextQuestions.parse(
                  "trying_to_conceive",
                  1,
                  "YES_NO",
                  "PROFILE",
                  json("{\"en\":{\"question\":\"?\"},\"fr\":{\"question\":\"?\"}}"),
                  json("[]")),
              ContextQuestions.parse(
                  "contraception",
                  1,
                  "MANY_CHOICES",
                  "ENROLMENT",
                  json("{\"en\":{\"question\":\"?\"},\"fr\":{\"question\":\"?\"}}"),
                  json(
                      "[{\"code\":\"pill\",\"labels\":{\"en\":\"Pill\",\"fr\":\"Pilule\"}},"
                          + "{\"code\":\"iud\",\"labels\":{\"en\":\"IUD\",\"fr\":\"DIU\"}}]")));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Test
  void anAnswerThatMatchesSwitchesTheRuleOff() throws Exception {
    var unless = json("[{\"question\":\"trying_to_conceive\",\"equals\":true}]");
    assertThat(Unless.blocks(unless, Map.of("trying_to_conceive", BooleanNode.TRUE), null))
        .isTrue();
    assertThat(Unless.blocks(unless, Map.of("trying_to_conceive", BooleanNode.FALSE), null))
        .isFalse();
  }

  @Test
  void aSkippedQuestionSwitchesNothingOff() throws Exception {
    var unless = json("[{\"question\":\"trying_to_conceive\",\"equals\":false}]");
    assertThat(Unless.blocks(unless, Map.of(), null)).isFalse();
  }

  @Test
  void includesReadsAListOfChoices() throws Exception {
    var unless = json("[{\"question\":\"contraception\",\"includes\":\"pill\"}]");
    assertThat(Unless.blocks(unless, Map.of("contraception", json("[\"iud\",\"pill\"]")), null))
        .isTrue();
    assertThat(Unless.blocks(unless, Map.of("contraception", json("[\"iud\"]")), null)).isFalse();
  }

  @Test
  void oldProfileFieldsAreReadThroughTheQuestionsThatReplacedThem() throws Exception {
    var hormonal = json("[{\"field\":\"treatments\",\"includes\":\"hormonal\"}]");
    assertThat(Unless.blocks(hormonal, Map.of("hormonal_treatment", BooleanNode.TRUE), null))
        .isTrue();
    assertThat(Unless.blocks(hormonal, Map.of("hormonal_treatment", BooleanNode.FALSE), null))
        .isFalse();
    var trying = json("[{\"field\":\"trying_to_conceive\",\"equals\":true}]");
    assertThat(Unless.blocks(trying, Map.of("trying_to_conceive", BooleanNode.TRUE), null))
        .isTrue();
    var enrolment = json("[{\"field\":\"enrolment_answer\",\"equals\":\"yes\"}]");
    assertThat(Unless.blocks(enrolment, Map.of(), "yes")).isTrue();
    assertThat(Unless.blocks(enrolment, Map.of(), null)).isFalse();
    var pcos = json("[{\"field\":\"pcos_status\",\"equals\":\"DIAGNOSED\"}]");
    assertThat(Unless.blocks(pcos, Map.of("pcos_status", TextNode.valueOf("DIAGNOSED")), null))
        .isFalse();
  }

  @Test
  void theValidatorNamesUnknownQuestionsAndAnswersThatCannotBeGiven() throws Exception {
    assertThat(
            Unless.problems(
                json(
                    "[{\"question\":\"trying_to_conceive\",\"equals\":true},"
                        + "{\"question\":\"contraception\",\"includes\":\"iud\"},"
                        + "{\"field\":\"treatments\",\"includes\":\"hormonal\"}]"),
                QUESTIONS))
        .isEmpty();
    assertThat(Unless.problems(json("[{\"question\":\"pregnant\",\"equals\":true}]"), QUESTIONS))
        .containsExactly("unless: no published question pregnant");
    assertThat(
            Unless.problems(
                json("[{\"question\":\"trying_to_conceive\",\"equals\":\"yes\"}]"), QUESTIONS))
        .containsExactly("unless: not an answer to trying_to_conceive");
    assertThat(
            Unless.problems(
                json("[{\"question\":\"contraception\",\"includes\":\"ring\"}]"), QUESTIONS))
        .containsExactly("unless: not an answer to contraception");
    assertThat(Unless.problems(json("[{\"field\":\"age\",\"equals\":30}]"), QUESTIONS))
        .containsExactly("unless: age");
  }
}
