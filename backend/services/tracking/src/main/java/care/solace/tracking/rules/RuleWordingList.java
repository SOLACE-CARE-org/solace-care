package care.solace.tracking.rules;

import care.solace.lists.ReviewedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * The words a rule's message may not use, and the phrases that are allowed anyway, as a reviewed
 * list (D61). A clinician drafts a change; another signs it off.
 */
@Component
class RuleWordingList implements ReviewedList {

  private final ObjectMapper json;

  RuleWordingList(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public String code() {
    return "tracking.rules.wording";
  }

  @Override
  public String description() {
    return "Wording a rule's message may not use (blocked), and phrases allowed anyway";
  }

  @Override
  public JsonNode defaults() {
    return json.valueToTree(
        Map.of("blocked", RuleRules.blockedDefaults(), "allowed", RuleRules.allowedDefaults()));
  }

  @Override
  public void validate(JsonNode entries) {
    blocked(entries);
    allowed(entries);
  }

  @Override
  public void apply(JsonNode live, int version) {
    RuleRules.useWording(blocked(live), allowed(live));
  }

  /** Regular expressions on lower-case text without accents; "triage" always stays. */
  private static List<String> blocked(JsonNode entries) {
    JsonNode list = entries.path("blocked");
    if (!list.isArray() || list.isEmpty() || list.size() > 200) {
      throw new IllegalArgumentException("blocked: 1 to 200 patterns");
    }
    List<String> out = new ArrayList<>();
    for (JsonNode p : list) {
      try {
        out.add(Pattern.compile(p.asText()).pattern());
      } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException("not a pattern: " + p.asText());
      }
    }
    if (out.stream().noneMatch(p -> p.contains("triage"))) {
      throw new IllegalArgumentException("the list must keep \"triage\"");
    }
    return out;
  }

  private static List<String> allowed(JsonNode entries) {
    JsonNode list = entries.path("allowed");
    if (!list.isArray() || list.size() > 50) {
      throw new IllegalArgumentException("allowed: up to 50 phrases");
    }
    List<String> out = new ArrayList<>();
    list.forEach(p -> out.add(p.asText().toLowerCase(java.util.Locale.ROOT)));
    return out;
  }
}
