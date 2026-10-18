package care.solace.core.bookings;

import care.solace.errors.ErrorCode;

/** Errors of bookings; titles in messages.properties and messages_fr.properties. */
enum BookingError implements ErrorCode {
  /** The hold expired or was never made: pick a time again. */
  HOLD_EXPIRED("booking.hold-expired", 409),
  /** The specialist is not bookable in that session language, or that format. */
  NOT_BOOKABLE("booking.not-bookable", 400),
  /** Only seekers book (staff and specialists do not). */
  NOT_A_SEEKER("booking.not-a-seeker", 403),
  /** The booking's state does not allow it (already cancelled, past, in session). */
  NOT_CANCELLABLE("booking.not-cancellable", 409),
  /** No reschedules left under the booking's policy. */
  RESCHEDULE_LIMIT("booking.reschedule-limit", 409),
  /** Too close to the start to move it. */
  RESCHEDULE_TOO_LATE("booking.reschedule-too-late", 409),
  /** The new time is with another specialist or in another format. */
  RESCHEDULE_MISMATCH("booking.reschedule-mismatch", 400);

  private final String code;
  private final int status;

  BookingError(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String area() {
    return "BKG";
  }
}
