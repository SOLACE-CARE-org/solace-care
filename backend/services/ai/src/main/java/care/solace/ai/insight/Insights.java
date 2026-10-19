package care.solace.ai.insight;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.privacy.AiConsent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tracking insights (System Design, AI feature 2; D56): a recommendation worded plainly from its
 * evidence only. Rules decide; this only explains. Any wording that names a condition, a number not
 * in the evidence, or runs long is thrown away and the rule's message stays.
 */
@Service
public class Insights implements Evaluations.Runner {

  static final int MAX = 320;

  public record Wording(String en, String fr, String promptVersion) {}

  private final Features features;
  private final ModelGateway gateway;
  private final AiConsent consents;
  private final ObjectMapper json;

  Insights(Features features, ModelGateway gateway, AiConsent consents, ObjectMapper json) {
    this.features = features;
    this.gateway = gateway;
    this.consents = consents;
    this.json = json;
  }

  /** For a seeker: only with the live version, and only when they said yes to AI_ASSIST. */
  public Optional<Wording> explain(
      UUID subject, String ruleCode, String evidence, String messageEn, String messageFr) {
    var request = request(null, subject, ruleCode, evidence, messageEn, messageFr);
    var live = features.live(Feature.TRACKING_INSIGHTS);
    if (live.isEmpty() || !consents.granted(subject, "AI_ASSIST")) {
      gateway.off(request);
      return Optional.empty();
    }
    return word(live.get(), subject, ruleCode, evidence, messageEn, messageFr).wording();
  }

  record Outcome(Optional<Wording> wording, String failure) {}

  Outcome word(
      Features.PromptVersion v,
      UUID subject,
      String ruleCode,
      String evidence,
      String messageEn,
      String messageFr) {
    var request = request(v, subject, ruleCode, evidence, messageEn, messageFr);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return new Outcome(Optional.empty(), "no-answer");
    }
    JsonNode parsed;
    try {
      String text = answer.get().text();
      int start = text.indexOf('{');
      int end = text.lastIndexOf('}');
      parsed = json.readTree(start >= 0 && end > start ? text.substring(start, end + 1) : text);
    } catch (Exception e) {
      gateway.refused(request, answer.get().route(), answer.get().text());
      return new Outcome(Optional.empty(), "not-json");
    }
    String en = parsed.path("en").asText("").strip();
    String fr = parsed.path("fr").asText("").strip();
    // The rule's own message may carry its threshold and counts; nothing else may.
    String facts = evidence + " " + messageEn + " " + messageFr;
    for (String text : new String[] {en, fr}) {
      Optional<String> refused =
          Guardrails.length(text, MAX)
              .or(() -> Guardrails.clinical(text))
              .or(() -> Guardrails.numbersFrom(text, facts));
      if (refused.isPresent()) {
        gateway.refused(request, answer.get().route(), answer.get().text());
        return new Outcome(Optional.empty(), refused.get());
      }
    }
    return new Outcome(Optional.of(new Wording(en, fr, v.version())), null);
  }

  private static ModelGateway.Request request(
      Features.PromptVersion v,
      UUID subject,
      String ruleCode,
      String evidence,
      String messageEn,
      String messageFr) {
    String user =
        "Rule: "
            + ruleCode
            + "\nEvidence: "
            + evidence
            + "\nReviewed message (EN): "
            + messageEn
            + "\nReviewed message (FR): "
            + messageFr;
    return new ModelGateway.Request(
        Feature.TRACKING_INSIGHTS,
        subject,
        v == null ? null : v.version(),
        v == null ? "" : v.systemPrompt(),
        user,
        true);
  }

  // --- Evaluation -------------------------------------------------------------------------------

  @Override
  public Feature feature() {
    return Feature.TRACKING_INSIGHTS;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    var outcome =
        word(
            version,
            null,
            input.path("ruleCode").asText(),
            input.path("evidence").toString(),
            input.path("messageEn").asText(),
            input.path("messageFr").asText());
    if (outcome.wording().isEmpty()) {
      return Optional.of(outcome.failure());
    }
    String text =
        "fr".equals(locale.getLanguage())
            ? outcome.wording().get().fr()
            : outcome.wording().get().en();
    for (JsonNode must : expect.path("mentions")) {
      if (!text.toLowerCase(Locale.ROOT).contains(must.asText().toLowerCase(Locale.ROOT))) {
        return Optional.of("missing:" + must.asText());
      }
    }
    return Optional.empty();
  }
}
