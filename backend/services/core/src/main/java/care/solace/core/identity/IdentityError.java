package care.solace.core.identity;

import care.solace.errors.ErrorCode;

/** Errors of the identity module; titles in messages.properties and messages_fr.properties. */
enum IdentityError implements ErrorCode {
  /** The seeker is under 18 (SeekerSignup: "You need to be 18 or over to use Solace."). */
  TOO_YOUNG("account.too-young", 400),
  /** The Terms or privacy notice version is not the current one: the app is out of date. */
  OUTDATED_TERMS("account.outdated-terms", 400),
  /** A staff or service token on an endpoint for seekers and specialists. */
  NOT_A_MEMBER("account.not-a-member", 403),
  /** A code or language that is not in the catalogue. */
  UNKNOWN_CHOICE("account.unknown-choice", 400);

  private final String code;
  private final int status;

  IdentityError(String code, int status) {
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
    return "ACC";
  }
}
