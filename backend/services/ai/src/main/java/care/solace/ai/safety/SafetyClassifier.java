package care.solace.ai.safety;

import java.util.Locale;
import java.util.Optional;

/** Finds crisis risk in text (System Design, AI: the safety monitor; D39). */
interface SafetyClassifier {

  /** The six categories, most severe first. */
  enum Category {
    SUICIDAL_IDEATION,
    SELF_HARM,
    VIOLENT_THOUGHTS,
    SUBSTANCE_CRISIS,
    RISK_TAKING,
    ACUTE_ANXIETY
  }

  /** Recorded with each decision (AI-9). */
  String version();

  /** The category of risk the text shows, if any. */
  Optional<Category> classify(String text, Locale locale);
}
