package care.solace.core.guests;

import care.solace.errors.ErrorCode;

/** Errors of guest access; titles in messages.properties and messages_fr.properties. */
enum GuestError implements ErrorCode {
  /** Wrong, used or expired code; the reply does not say which. */
  CODE_REJECTED("guest.code-rejected", 400),
  /** A missing, wrong or expired guest token. */
  TOKEN_INVALID("guest.token-invalid", 401);

  private final String code;
  private final int status;

  GuestError(String code, int status) {
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
    return "GST";
  }
}
