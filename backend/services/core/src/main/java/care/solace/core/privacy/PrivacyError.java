package care.solace.core.privacy;

import care.solace.errors.ErrorCode;

/** Errors of privacy; titles in messages.properties and messages_fr.properties. */
enum PrivacyError implements ErrorCode {
  /** A paid session is still to come or under way (D40). */
  UPCOMING_SESSIONS("privacy.upcoming-sessions", 409),
  /** A refund is still being decided or sent (D40). */
  OPEN_REFUND("privacy.open-refund", 409),
  /** The copy is still being prepared, or has expired. */
  EXPORT_NOT_READY("privacy.export-not-ready", 409);

  private final String code;
  private final int status;

  PrivacyError(String code, int status) {
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
    return "PRV";
  }
}
