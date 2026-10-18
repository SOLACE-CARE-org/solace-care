package care.solace.core.scheduling;

import care.solace.errors.ErrorCode;

/** Errors of the scheduling module; titles in messages.properties and messages_fr.properties. */
enum SchedulingError implements ErrorCode {
  /** Weekly hours are for specialists. */
  NOT_A_SPECIALIST("specialist.not-a-specialist", 403),
  /** Two ranges on the same day overlap, or a day is listed twice. */
  OVERLAPPING_HOURS("scheduling.overlapping-hours", 400),
  /** The start is booked, held by someone else, blocked, or no longer offered. */
  SLOT_TAKEN("slot.taken", 409),
  /** Time off would overlap a booking or other time off. */
  TIME_OFF_CONFLICT("scheduling.time-off-conflict", 409);

  private final String code;
  private final int status;

  SchedulingError(String code, int status) {
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
    return "SCH";
  }
}
