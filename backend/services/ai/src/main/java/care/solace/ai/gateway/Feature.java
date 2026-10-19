package care.solace.ai.gateway;

/** The AI features that call a model through the gateway (System Design, AI service; D56). */
public enum Feature {
  TRACKING_INSIGHTS,
  PRE_SESSION_BRIEF,
  REVIEW_MODERATION,
  TRANSLATION,
  /** Phase 4: transcripts and SOAP or DAP drafts (D57). */
  SESSION_SCRIBE,
  /** Phase 4: the homework companion's reflections, from vetted content only (D57). */
  COMPANION,
  /** Phase 4: questions over aggregated metrics (admin screen 27). */
  ADMIN_COPILOT
}
