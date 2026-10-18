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

  /** CONFIRMED bookings that started before {@code before}: not begun, maybe a no-show (D36). */
  java.util.List<Info> confirmedStartedBefore(Instant before);

  /** Every booking a member made, for their copy of their data and for erasure (D40). */
  java.util.List<UUID> idsFor(UUID accountId);

  /** Every booking of a person, by account or guest contact id, newest first (People, D45). */
  java.util.List<Info> forSubject(UUID subjectId);

  /** The one booking a reference (BK-7F9D1C2E) or id names, if exactly one (D41). */
  Optional<Info> byReference(String reference);

  /** The person's next confirmed session, by account or guest contact id (crisis queue). */
  Optional<Info> nextFor(UUID subjectId, Instant now);

  /** CONFIRMED bookings starting in [from, to), for reminders (D38). */
  java.util.List<Info> confirmedStartingBetween(Instant from, Instant to);

  /** IN_PROGRESS bookings that ended before {@code before}: due to complete (D36). */
  java.util.List<Info> inProgressEndedBefore(Instant before);

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

    /** "#5480", or "G-2244" for a guest, as staff screens name people without names (D41). */
    public String personRef() {
      return BookingRecords.personRef(subjectId(), seekerAccountId == null);
    }
  }

  /** "#5480", or "G-2244" for a guest: how staff screens name a person without a name (D41). */
  static String personRef(UUID subjectId, boolean guest) {
    String digits = String.format("%04d", Math.floorMod(subjectId.hashCode(), 10_000));
    return guest ? "G-" + digits : "#" + digits;
  }
}
