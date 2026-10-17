package care.solace.core.specialists;

/**
 * The onboarding state machine (System Design, Specialists): DRAFT → SUBMITTED → UNDER_REVIEW →
 * APPROVED or REJECTED; APPROVED → SUSPENDED. A change request returns the application to DRAFT
 * (D26).
 */
enum ApplicationStatus {
  DRAFT,
  SUBMITTED,
  UNDER_REVIEW,
  APPROVED,
  REJECTED,
  SUSPENDED
}
