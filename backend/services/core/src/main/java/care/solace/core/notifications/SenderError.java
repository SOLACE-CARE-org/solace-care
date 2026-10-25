package care.solace.core.notifications;

import care.solace.errors.ErrorCode;

/** Errors of managing SMS and email senders (D61); titles in messages.properties and _fr. */
enum SenderError implements ErrorCode {
  /** A sender with this code exists. */
  EXISTS("message-sender.exists", 409),
  /** The sender's version is not the one read. */
  CHANGED("message-sender.changed", 409),
  /** The adapter does not suit the channel, or its settings are not valid. */
  INVALID("message-sender.invalid", 400),
  /** ACTIVE without valid settings or every key. */
  NOT_READY("message-sender.not-ready", 409),
  /** The change would leave the channel with no ACTIVE sender. */
  LAST_ACTIVE("message-sender.last-active", 409);

  private final String code;
  private final int status;

  SenderError(String code, int status) {
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
    return "NOT";
  }
}
