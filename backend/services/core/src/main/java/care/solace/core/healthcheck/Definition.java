package care.solace.core.healthcheck;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A health check version's definition as clinicians write it (System Design, Health check; D55):
 * questions in order, rules that add points to specialties, the order that breaks ties, and made-up
 * answer sets for testing. Stored as JSON on the version.
 */
final class Definition {

  private Definition() {}

  enum Kind {
    CHOICE,
    SAFETY,
    SCREENER
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Option(String code, String labelEn, String labelFr, Integer value, String topic) {}

  /**
   * Shown when an option of another question was chosen, or a screener's first items reach a total.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShowIf(
      String question, List<String> anyOf, String screener, Integer firstItems, Integer atLeast) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Question(
      String code,
      Kind kind,
      boolean multi,
      int step,
      String textEn,
      String textFr,
      String introEn,
      String introFr,
      String screener,
      Integer item,
      Boolean locked,
      List<Option> options,
      ShowIf showIf) {

    boolean isLocked() {
      return kind == Kind.SAFETY || kind == Kind.SCREENER;
    }

    Question withLocked() {
      return new Question(
          code,
          kind,
          multi,
          step,
          textEn,
          textFr,
          introEn,
          introFr,
          screener,
          item,
          isLocked(),
          options,
          showIf);
    }
  }

  /** One condition: an option chosen, or a screener total at least. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record When(
      String question, List<String> anyOf, String screener, Integer atLeast, Integer atMost) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Rule(
      String code,
      List<When> when,
      String specialty,
      int points,
      String reasonEn,
      String reasonFr) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TestSet(String name, Map<String, List<String>> answers, String expected) {}
}
