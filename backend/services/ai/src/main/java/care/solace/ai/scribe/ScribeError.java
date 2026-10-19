package care.solace.ai.scribe;

import care.solace.errors.ErrorCode;

/** Errors of notes and homework; titles in messages.properties and messages_fr.properties. */
public enum ScribeError implements ErrorCode {
  /** Only a draft can be edited, redrafted, signed or discarded. */
  NOT_A_DRAFT("notes.not-a-draft", 409),
  /** Every section needs the specialist's words before signing, the assessment above all. */
  INCOMPLETE("notes.incomplete", 409),
  /** The transcript is gone (signed, or never made), so there is nothing to redraft from. */
  NO_TRANSCRIPT("notes.no-transcript", 409),
  /** Homework is set for a registered seeker after a session with them. */
  NO_SESSION("homework.no-session", 403),
  /** The homework is complete, or past its date. */
  HOMEWORK_CLOSED("homework.closed", 409);

  private final String code;
  private final int status;

  ScribeError(String code, int status) {
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
