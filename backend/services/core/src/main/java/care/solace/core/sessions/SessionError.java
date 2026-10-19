package care.solace.core.sessions;

import care.solace.errors.ErrorCode;

/** Errors of sessions; titles in messages.properties and messages_fr.properties. */
enum SessionError implements ErrorCode {
  /** Joining opens 10 minutes before the start (D18). */
  NOT_OPEN_YET("session.not-open-yet", 409),
  /** The session is over, cancelled, or was never confirmed. */
  NOT_JOINABLE("session.not-joinable", 409),
  /** Joining by phone is not switched on, or no number is set up (D59). */
  NO_DIAL_IN("session.no-dial-in", 409),
  /** The media server's webhook did not verify. */
  SIGNATURE_INVALID("session.signature-invalid", 401);

  private final String code;
  private final int status;

  SessionError(String code, int status) {
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
    return "SES";
  }
}
