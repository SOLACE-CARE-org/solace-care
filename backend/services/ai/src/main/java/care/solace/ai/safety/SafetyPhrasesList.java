package care.solace.ai.safety;

import care.solace.lists.ReviewedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The safety monitor's phrases as a reviewed list (D61): one list of phrases per category, in EN
 * and FR. A draft goes live only when the safety set reaches the recall gate on it, 99% in each
 * language, and a second clinician signs it off.
 */
@Component
class SafetyPhrasesList implements ReviewedList {

  static final String CODE = "ai.safety.phrases";

  private final ObjectMapper json;
  private final ObjectProvider<SafetyEvaluation> evaluation;

  SafetyPhrasesList(ObjectMapper json, ObjectProvider<SafetyEvaluation> evaluation) {
    this.json = json;
    this.evaluation = evaluation;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public String description() {
    return "Safety monitor phrases by category; the safety set must pass on them";
  }

  @Override
  public JsonNode defaults() {
    return json.valueToTree(PhraseClassifier.defaults());
  }

  @Override
  public void validate(JsonNode entries) {
    phrases(entries);
  }

  @Override
  public Optional<String> check(JsonNode entries) {
    var result = run(entries);
    return result.passing()
        ? Optional.empty()
        : Optional.of(
            "Safety set recall EN %.1f, FR %.1f, below %.0f"
                .formatted(result.recallEn(), result.recallFr(), result.gate()));
  }

  @Override
  public String checkSummary(JsonNode entries) {
    var result = run(entries);
    return "recall EN %.1f, FR %.1f".formatted(result.recallEn(), result.recallFr());
  }

  @Override
  public void apply(JsonNode live, int version) {
    // Recorded with each decision (AI-9): version 0 is the code's "phrases-1".
    PhraseClassifier.use(
        version == 0 ? PhraseClassifier.VERSION : "phrases-r" + version, phrases(live));
  }

  private SafetyEvaluation.Result run(JsonNode entries) {
    return evaluation.getObject().evaluate(new PhraseClassifier("draft", phrases(entries)));
  }

  /** Every category, each with at least one phrase; nothing else. */
  private static Map<SafetyClassifier.Category, List<String>> phrases(JsonNode entries) {
    if (!entries.isObject()) {
      throw new IllegalArgumentException("phrases by category");
    }
    Map<SafetyClassifier.Category, List<String>> byCategory =
        new EnumMap<>(SafetyClassifier.Category.class);
    for (var c : SafetyClassifier.Category.values()) {
      JsonNode list = entries.path(c.name());
      if (!list.isArray() || list.isEmpty()) {
        throw new IllegalArgumentException("every category needs phrases: " + c);
      }
      List<String> phrases = new ArrayList<>();
      for (JsonNode p : list) {
        if (!p.isTextual() || p.asText().isBlank() || p.asText().length() > 120) {
          throw new IllegalArgumentException("a phrase is text of at most 120 characters");
        }
        phrases.add(p.asText());
      }
      byCategory.put(c, List.copyOf(phrases));
    }
    entries
        .fieldNames()
        .forEachRemaining(
            name -> {
              try {
                SafetyClassifier.Category.valueOf(name);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown category " + name);
              }
            });
    return byCategory;
  }
}
