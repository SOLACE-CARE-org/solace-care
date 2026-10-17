package care.solace.core.privacy;

/** What a consent covers (System Design, Controls: Consent; contracts ConsentPurpose). */
public enum Purpose {
  /** Health tracking (Phase 2). */
  TRACKING,
  /** Plain-language insights and the between-session companion (Phases 3 and 4). */
  AI_ASSIST,
  /** Health check answers and tracking trends shown to the specialist before each session. */
  SPECIALIST_SHARING,
  /** The AI scribe in the person's sessions (Phase 4). */
  AI_SCRIBE,
  /** Health check answers (Phase 2), asked before the first question. */
  HEALTH_CHECK
}
