package care.solace.core.content;

import care.solace.errors.ErrorCode;

/** Errors of the content module; titles in messages.properties and messages_fr.properties. */
enum ContentError implements ErrorCode {
  /** Another item already has this address. */
  SLUG_TAKEN("content.slug-taken", 409),
  /** A language cannot be done while its title or text is empty. */
  LANGUAGE_EMPTY("content.language-empty", 409),
  /** EN and FR are not both done, a clinical check is missing, or nothing changed. */
  NOT_READY("content.not-ready", 409),
  /** The clinical check is not in a state that allows this. */
  WRONG_STATE("content.wrong-state", 409);

  private final String code;
  private final int status;

  ContentError(String code, int status) {
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
    return "CNT";
  }
}
