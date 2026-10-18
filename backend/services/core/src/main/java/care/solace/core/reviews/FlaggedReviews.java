package care.solace.core.reviews;

import java.util.Optional;
import java.util.UUID;

/**
 * Reviews the safety monitor flagged, as the crisis queue needs them (D39): only the flagged text,
 * nothing else the person wrote. Reading it is audited by the caller.
 */
public interface FlaggedReviews {

  Optional<Flagged> find(UUID reviewId);

  /** Takes the review out of the moderation queue for good (D37); idempotent. */
  void flag(UUID reviewId);

  /**
   * @param language the session language, the text's likely language
   */
  record Flagged(
      UUID reviewId, UUID bookingId, UUID subjectId, boolean guest, String text, String language) {}
}
