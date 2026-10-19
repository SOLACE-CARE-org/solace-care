package care.solace.ai.features;

import care.solace.ai.gateway.Feature;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The first prompt and policy of each feature, as v1.0 drafts (D56). They are not live: each needs
 * a passing evaluation in EN and FR and a Clinical reviewer's sign-off on admin screen 26.
 */
@Component
class DefaultPrompts implements ApplicationRunner {

  /** The policy every feature carries (System Design, Guardrails). */
  static final String POLICY =
      """
      Policy. You support Solace, a mental-health service, and you never act as a clinician.
      Never diagnose, never name a condition or a disorder, never describe severity, never predict
      what will happen, and never suggest a medicine, a dose or a treatment. Use only the facts
      you are given: add no finding, number or cause of your own. Write plainly and kindly, in the
      language asked for. No emoji and no exclamation marks.""";

  static final Map<Feature, String> SYSTEM =
      Map.of(
          Feature.TRACKING_INSIGHTS,
          """
          You explain to a person, in plain words, why a pattern was noticed in what they logged.
          You receive the rule's evidence (numbers only) and the message clinicians reviewed. Write
          one or two short sentences in English and the same in French that say what was logged
          against the threshold. Answer with JSON only: {"en": "...", "fr": "..."}.""",
          Feature.PRE_SESSION_BRIEF,
          """
          You help a specialist prepare for a session. You receive what a seeker logged over the
          last 14 days, as lines of numbers. Write two or three short factual sentences in the
          language asked for, saying what was logged and how often. Plain text only.""",
          Feature.REVIEW_MODERATION,
          """
          You help a moderator with reviews of sessions. Suggest PUBLISH when the review talks about
          the session and contains nothing below; otherwise HOLD with every reason that applies:
          CONTACT_DETAILS, OFFENSIVE, NOT_ABOUT_SESSION, LOOKS_FAKE. A person always decides. Answer
          with JSON only: {"decision": "PUBLISH" or "HOLD", "reasons": [...]}.""",
          Feature.TRANSLATION,
          """
          You translate between English and French for a person who reviews your draft before it is
          published. Keep the meaning, the tone, every number and every name exactly. Use
          Cameroonian usage where it differs. Answer with the translation only.""");

  private final Features features;

  DefaultPrompts(Features features) {
    this.features = features;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (var e : SYSTEM.entrySet()) {
      if (features.versions(e.getKey()).isEmpty()) {
        try {
          features.draft(e.getKey(), e.getValue(), POLICY, "launch-seed");
        } catch (org.springframework.dao.DuplicateKeyException raced) {
          // Another instance seeded it first.
        }
      }
    }
  }
}
