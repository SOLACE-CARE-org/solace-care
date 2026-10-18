package care.solace.tracking;

import care.solace.errors.ErrorCode;

/** Tracking's errors; titles in messages.properties and messages_fr.properties. */
public enum TrackingError implements ErrorCode {
  /**
   * Tracking needs a seeker's account; guests, specialists and staff cannot log (Spec, Principles).
   */
  SEEKERS_ONLY("tracking.seekers-only", 403),
  /** The person has not said yes to tracking, or withdrew it. */
  CONSENT_NEEDED("tracking.consent-needed", 403),
  /** The module, version or rule is not in a state that allows this. */
  WRONG_STATE("tracking.wrong-state", 409),
  /** The same person cannot draft and approve one version (Spec, Versions and lifecycle). */
  SAME_PERSON("tracking.same-person", 409),
  /** Publishing needs EN and FR, an image, alt text, who it is for and 1 to 6 elements. */
  NOT_READY("tracking.not-ready", 409),
  /** The message uses diagnostic wording the word list blocks. */
  BLOCKED_WORDING("tracking.blocked-wording", 400),
  /** Too young for this module, or the age could not be checked. */
  NOT_ELIGIBLE("tracking.not-eligible", 403),
  /** No live booking with this seeker, or no sharing grant. */
  NO_ACCESS("tracking.no-access", 403);

  private final String code;
  private final int status;

  TrackingError(String code, int status) {
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
    return "TRK";
  }
}
