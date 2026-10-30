package care.solace.core.specialists;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside Core when a specialist's application moves on, for their messages (epic 9).
 *
 * @param kind SUBMITTED, CHANGES_REQUESTED, INTERVIEW_BOOKED, APPROVED, REJECTED, SUSPENDED, or
 *     PRACTICE_DECIDED for a profession, specialty or specialty request (D62)
 * @param interviewAt INTERVIEW_BOOKED only
 */
public record ApplicationUpdated(UUID specialistId, String kind, Instant interviewAt) {}
