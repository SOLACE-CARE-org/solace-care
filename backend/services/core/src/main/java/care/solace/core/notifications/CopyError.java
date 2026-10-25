package care.solace.core.notifications;

import care.solace.errors.ErrorCode;

/** Errors of editing notification copy (D61); titles in messages.properties and messages_fr. */
enum CopyError implements ErrorCode {
  /** Not the approved wording's placeholders, or an unbalanced brace. */
  PLACEHOLDERS("copy.placeholders", 400),
  /** An exclamation mark, an emoji, or a word that says what the message is about. */
  WORDING("copy.wording", 400),
  /** An SMS character outside GSM-7, or an accent French SMS avoid. */
  SMS_CHARACTERS("copy.sms-characters", 400),
  /** An SMS longer outside its placeholders than the approved one: it may not fit 160 filled. */
  SMS_TOO_LONG("copy.sms-too-long", 400),
  /** The message's version is not the one read. */
  CHANGED("copy.changed", 409);

  private final String code;
  private final int status;

  CopyError(String code, int status) {
    this.code = code;
    this.status = status;
  }

  static CopyError of(CopyRules.Problem problem) {
    return switch (problem) {
      case PLACEHOLDERS -> PLACEHOLDERS;
      case WORDING -> WORDING;
      case SMS_CHARACTERS -> SMS_CHARACTERS;
      case SMS_TOO_LONG -> SMS_TOO_LONG;
    };
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
