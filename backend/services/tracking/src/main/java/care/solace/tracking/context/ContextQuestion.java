package care.solace.tracking.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Something about the patient that can switch a rule off, such as "Are you trying to conceive?"
 * (Tracking Service Spec, Context questions; D61). A reviewed definition: answers can only switch
 * rules off, never on, and a skipped question switches nothing.
 *
 * @param labels {"en": {"question": ...}, "fr": {"question": ...}}
 * @param options the choices of a ONE_CHOICE or MANY_CHOICES question; empty for YES_NO
 */
public record ContextQuestion(
    String code, int version, Type type, Scope scope, JsonNode labels, List<Option> options) {

  public enum Type {
    YES_NO,
    ONE_CHOICE,
    MANY_CHOICES
  }

  public enum Scope {
    /** Asked on the app's "About you for tracking" screen and editable there. */
    PROFILE,
    /** Asked only when enrolling in a module that lists it. */
    ENROLMENT
  }

  /**
   * @param labels {"en": "...", "fr": "..."}
   */
  public record Option(String code, JsonNode labels) {}

  public boolean hasOption(String code) {
    return options.stream().anyMatch(o -> o.code().equals(code));
  }

  /** True when the value is a possible answer: a boolean, one option, or a list of options. */
  public boolean accepts(JsonNode value) {
    if (value == null) {
      return false;
    }
    return switch (type) {
      case YES_NO -> value.isBoolean();
      case ONE_CHOICE -> value.isTextual() && hasOption(value.asText());
      case MANY_CHOICES -> {
        if (!value.isArray() || value.size() > options.size()) {
          yield false;
        }
        for (JsonNode v : value) {
          if (!v.isTextual() || !hasOption(v.asText())) {
            yield false;
          }
        }
        yield true;
      }
    };
  }
}
