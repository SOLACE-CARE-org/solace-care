package care.solace.ai.safety;

import care.solace.ai.features.EvalSets;
import care.solace.lists.ReviewedLists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The safety monitor's release gate on admin screen 26 (System Design, Evaluation: crisis recall at
 * least 99% on the safety set in EN and FR; D56). The set is supplied and maintained by the
 * clinical team at {@code /eval/safety-monitor.json} (cases with "text", "locale" and "crisis");
 * until it is there, the monitor shows as not evaluated rather than passing.
 */
@Service
public class SafetyEvaluation {

  public record Result(
      String classifier,
      String policy,
      double recallEn,
      double recallFr,
      double gate,
      boolean passing) {}

  private final List<SafetyClassifier> classifiers;
  private final SafetySettings settings;
  private final ObjectMapper json;
  private final double gate;
  private final ObjectProvider<ReviewedLists> lists;

  SafetyEvaluation(
      SafetyMonitor monitor,
      SafetySettings settings,
      ObjectMapper json,
      @Value("${solace.safety.recall-gate:99}") double gate,
      ObjectProvider<ReviewedLists> lists) {
    this.lists = lists;
    this.classifiers = monitor.classifiers();
    this.settings = settings;
    this.json = json;
    this.gate = gate;
  }

  public Result evaluate() {
    return evaluate(classifiers);
  }

  /** The safety set run on a candidate set of phrases, before it may go live (D61). */
  Result evaluate(SafetyClassifier candidate) {
    return evaluate(List.of(candidate));
  }

  private Result evaluate(List<SafetyClassifier> classifiers) {
    String version = classifiers.isEmpty() ? "none" : classifiers.getFirst().version();
    JsonNode cases = cases();
    int hitEn = 0;
    int allEn = 0;
    int hitFr = 0;
    int allFr = 0;
    for (JsonNode c : cases) {
      if (!c.path("crisis").asBoolean(false)) {
        continue;
      }
      boolean fr = "fr".equals(c.path("locale").asText());
      Locale locale = fr ? Locale.FRENCH : Locale.ENGLISH;
      String text = c.path("text").asText();
      boolean flagged = classifiers.stream().anyMatch(k -> k.classify(text, locale).isPresent());
      if (fr) {
        allFr++;
        hitFr += flagged ? 1 : 0;
      } else {
        allEn++;
        hitEn += flagged ? 1 : 0;
      }
    }
    double en = allEn == 0 ? 0 : 100.0 * hitEn / allEn;
    double frRecall = allFr == 0 ? 0 : 100.0 * hitFr / allFr;
    boolean passing = allEn > 0 && allFr > 0 && en >= gate && frRecall >= gate;
    return new Result(version, settings.policyVersion(), en, frRecall, gate, passing);
  }

  /** The safety set in force: the clinicians' reviewed list, or the file it started from. */
  private JsonNode cases() {
    var reviewed = lists.getIfAvailable();
    if (reviewed != null) {
      return reviewed.inForce(EvalSets.SAFETY).path("cases");
    }
    try (InputStream in = SafetyEvaluation.class.getResourceAsStream("/eval/safety-monitor.json")) {
      return in == null ? json.createArrayNode() : json.readTree(in).path("cases");
    } catch (IOException e) {
      return json.createArrayNode();
    }
  }
}
