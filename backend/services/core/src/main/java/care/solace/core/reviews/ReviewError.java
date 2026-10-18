package care.solace.core.reviews;

import care.solace.errors.ErrorCode;

/** Errors of ratings and reviews; titles in messages.properties and messages_fr.properties. */
enum ReviewError implements ErrorCode {
  /** Only completed sessions, within the rating window, can be rated. */
  NOT_RATEABLE("review.not-rateable", 409),
  /** The session was already rated. */
  ALREADY_RATED("review.already-rated", 409),
  /** The review is not waiting for moderation, or already has a reply. */
  WRONG_STATE("review.wrong-state", 409),
  /** Contact details must be masked before publishing (D37). */
  HAS_CONTACTS("review.has-contacts", 409),
  /** A reply may not carry contact details (D37). */
  REPLY_HAS_CONTACTS("review.reply-has-contacts", 400),
  /** Only specialists have reviews. */
  NOT_A_SPECIALIST("review.not-a-specialist", 403);

  private final String code;
  private final int status;

  ReviewError(String code, int status) {
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
    return "REV";
  }
}
