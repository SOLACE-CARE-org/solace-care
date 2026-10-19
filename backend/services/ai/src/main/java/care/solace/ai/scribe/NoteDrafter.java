package care.solace.ai.scribe;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Drafts a SOAP or DAP note from a session transcript (System Design, AI feature 4; D57). The model
 * writes only what was said: every section passes the guardrails (no diagnosis, no severity, no
 * number that was not said), and the assessment is always left empty for the specialist.
 */
@Component
class NoteDrafter implements Evaluations.Runner {

  /** The assessment is the specialist's clinical judgement; a model never writes it. */
  static final String ASSESSMENT = "A";

  private static final int SECTION_MAX = 2000;

  private final ModelGateway gateway;
  private final ObjectMapper json;

  NoteDrafter(ModelGateway gateway, ObjectMapper json) {
    this.gateway = gateway;
    this.json = json;
  }

  @Override
  public Feature feature() {
    return Feature.SESSION_SCRIBE;
  }

  /** The sections, or empty when no model answered or a guardrail refused the draft. */
  Optional<List<Notes.Section>> draft(
      Features.PromptVersion v, UUID subject, String transcript, String format, Locale language) {
    var request =
        new ModelGateway.Request(
            Feature.SESSION_SCRIBE,
            subject,
            v.version(),
            v.systemPrompt(),
            "Format: "
                + format
                + "\nWrite in: "
                + ("fr".equals(language.getLanguage()) ? "French" : "English")
                + "\nSections: "
                + String.join(", ", Notes.keys(format))
                + "\n\nTranscript:\n"
                + transcript,
            true);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    JsonNode node;
    try {
      node = json.readTree(strip(answer.get().text()));
    } catch (Exception e) {
      gateway.refused(request, answer.get().route(), answer.get().text());
      return Optional.empty();
    }
    List<Notes.Section> sections = new ArrayList<>();
    for (String key : Notes.keys(format)) {
      String text = key.equals(ASSESSMENT) ? "" : node.path(key).asText("").strip();
      // A section left empty (always the assessment) is the specialist's to write.
      if (!text.isEmpty() && Guardrails.clinical(text).isPresent()
          || !text.isEmpty() && Guardrails.numbersFrom(text, transcript).isPresent()
          || !text.isEmpty() && Guardrails.length(text, SECTION_MAX).isPresent()) {
        gateway.refused(request, answer.get().route(), answer.get().text());
        return Optional.empty();
      }
      sections.add(new Notes.Section(key, text));
    }
    if (sections.stream().allMatch(s -> s.text().isBlank())) {
      return Optional.empty();
    }
    return Optional.of(sections);
  }

  /**
   * A case passes when a draft comes back, its assessment is empty, and it mentions what it must
   * (made-up transcripts only; eval/session-scribe.json).
   */
  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    var drafted =
        draft(
            version,
            null,
            input.path("transcript").asText(),
            input.path("format").asText("SOAP"),
            locale);
    if (drafted.isEmpty()) {
      return Optional.of("no-draft-or-guardrail");
    }
    String all =
        String.join(" ", drafted.get().stream().map(Notes.Section::text).toList())
            .toLowerCase(Locale.ROOT);
    for (JsonNode m : expect.path("mentions")) {
      if (!all.contains(m.asText().toLowerCase(Locale.ROOT))) {
        return Optional.of("missing:" + m.asText());
      }
    }
    return Optional.empty();
  }

  private static String strip(String text) {
    String t = text.strip();
    if (t.startsWith("```")) {
      t = t.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").strip();
    }
    return t;
  }
}
