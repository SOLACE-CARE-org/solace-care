package care.solace.ai.admin;

import care.solace.ai.gateway.Guardrails;
import care.solace.lists.ReviewedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * The guardrail's clinical wording as a reviewed list (D61): patterns that keep a diagnosis, a
 * prescription or a prediction out of every AI text. A clinician drafts; another signs off.
 */
@Component
class ClinicalWordsList implements ReviewedList {

  private final ObjectMapper json;

  ClinicalWordsList(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public String code() {
    return "ai.guardrails.clinical";
  }

  @Override
  public String description() {
    return "Wording no AI text may contain: diagnoses, prescriptions, predictions";
  }

  @Override
  public JsonNode defaults() {
    return json.valueToTree(Guardrails.clinicalDefaults());
  }

  @Override
  public void validate(JsonNode entries) {
    patterns(entries);
  }

  @Override
  public void apply(JsonNode live, int version) {
    Guardrails.useClinical(patterns(live));
  }

  /** Each a regular expression on lower-case text without accents; "triage" always stays. */
  private static List<String> patterns(JsonNode entries) {
    if (!entries.isArray() || entries.isEmpty() || entries.size() > 200) {
      throw new IllegalArgumentException("a list of 1 to 200 patterns");
    }
    List<String> out = new ArrayList<>();
    for (JsonNode e : entries) {
      try {
        out.add(Pattern.compile(e.asText()).pattern());
      } catch (PatternSyntaxException ex) {
        throw new IllegalArgumentException("not a pattern: " + e.asText());
      }
    }
    // CLAUDE.md: the word "triage" is never used anywhere.
    if (out.stream().noneMatch(p -> p.contains("triage"))) {
      throw new IllegalArgumentException("the list must keep \"triage\"");
    }
    return List.copyOf(out);
  }
}
