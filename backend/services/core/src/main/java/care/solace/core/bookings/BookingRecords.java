package care.solace.core.bookings;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bookings as other modules see them: Payments confirms a paid booking (epic 6), Sessions starts,
 * completes or marks a no-show (epic 7). Each move follows the lifecycle (D32) and is refused
 * otherwise.
 */
public interface BookingRecords {

  Optional<Info> find(UUID bookingId);

  /** PENDING_PAYMENT to CONFIRMED; true only for the call that made the change. */
  boolean confirm(UUID bookingId, Instant now);

  /** CONFIRMED to IN_PROGRESS when the session starts. */
  boolean start(UUID bookingId, Instant now);

  /** IN_PROGRESS (or CONFIRMED) to COMPLETED. */
  boolean complete(UUID bookingId, Instant now);

  /** CONFIRMED or IN_PROGRESS to NO_SHOW, with who did not come. */
  boolean noShow(UUID bookingId, NoShowBy who, Instant now);

  /** Refund percent for a no-show under the booking's policy (D33). */
  int noShowRefundPercent(UUID bookingId, NoShowBy who);

  enum NoShowBy {
    SEEKER,
    SPECIALIST
  }

  /**
   * @param subjectId the seeker's account id, or the guest contact's id for a guest booking
   */
  record Info(
      UUID id,
      UUID seekerAccountId,
      UUID guestContactId,
      UUID specialistId,
      Instant start,
      Instant end,
      String modality,
      long price,
      String status,
      String sessionLanguage,
      Locale seekerLocale,
      String source,
      Instant payBy) {

    public UUID subjectId() {
      return seekerAccountId != null ? seekerAccountId : guestContactId;
    }
  }
}
