package care.solace.ai.moderation;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.gateway.Redactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A suggestion for the review moderator (System Design, AI feature 7; D56). Contact details are
 * caught by rules first; the model adds offensive, off-topic and fake-looking reviews. The
 * moderator still decides every review, and a crisis flag always wins.
 */
@Service
public class ReviewModeration implements Evaluations.Runner {

  static final Set<String> REASONS =
      Set.of("CONTACT_DETAILS", "OFFENSIVE", "NOT_ABOUT_SESSION", "LOOKS_FAKE");

  public record Suggestion(String decision, List<String> reasons) {}

  private final Features features;
  private final ModelGateway gateway;
  private final ObjectMapper json;

  ReviewModeration(Features features, ModelGateway gateway, ObjectMapper json) {
    this.features = features;
    this.gateway = gateway;
    this.json = json;
  }

  /** Empty when the feature is off or no model answered: the review simply waits for a person. */
  public Optional<Suggestion> suggest(UUID writer, String text) {
    return features.live(Feature.REVIEW_MODERATION).flatMap(v -> suggest(v, writer, text));
  }

  Optional<Suggestion> suggest(Features.PromptVersion v, UUID writer, String text) {
    Set<String> reasons = new LinkedHashSet<>();
    if (!Redactor.redact(text).equals(text)) {
      reasons.add("CONTACT_DETAILS");
    }
    var request =
        new ModelGateway.Request(
            Feature.REVIEW_MODERATION, writer, v.version(), v.systemPrompt(), text, false);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return reasons.isEmpty()
          ? Optional.empty()
          : Optional.of(new Suggestion("HOLD", List.copyOf(reasons)));
    }
    try {
      String t = answer.get().text();
      int start = t.indexOf('{');
      int end = t.lastIndexOf('}');
      JsonNode parsed = json.readTree(start >= 0 && end > start ? t.substring(start, end + 1) : t);
      for (JsonNode r : parsed.path("reasons")) {
        if (REASONS.contains(r.asText())) {
          reasons.add(r.asText());
        }
      }
      boolean hold = !reasons.isEmpty() || !"PUBLISH".equals(parsed.path("decision").asText());
      return Optional.of(new Suggestion(hold ? "HOLD" : "PUBLISH", new ArrayList<>(reasons)));
    } catch (Exception e) {
      gateway.refused(request, answer.get().route(), answer.get().text());
      return reasons.isEmpty()
          ? Optional.empty()
          : Optional.of(new Suggestion("HOLD", List.copyOf(reasons)));
    }
  }

  @Override
  public Feature feature() {
    return Feature.REVIEW_MODERATION;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    var s = suggest(version, null, input.path("text").asText());
    if (s.isEmpty()) {
      return Optional.of("no-suggestion");
    }
    String want = expect.path("decision").asText();
    return want.equals(s.get().decision()) ? Optional.empty() : Optional.of("expected:" + want);
  }
}
