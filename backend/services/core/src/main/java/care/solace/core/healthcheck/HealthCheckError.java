package care.solace.core.healthcheck;

import care.solace.errors.ErrorCode;

/** Errors of the health check; titles in messages.properties and messages_fr.properties. */
enum HealthCheckError implements ErrorCode {
  /** A seeker has not said yes to HEALTH_CHECK, or withdrew it. */
  CONSENT_NEEDED("health-check.consent-needed", 403),
  /** The version or response is not in a state that allows this. */
  WRONG_STATE("health-check.wrong-state", 409),
  /** The Clinical reviewer who wrote a version cannot approve it. */
  SAME_PERSON("health-check.same-person", 409),
  /** Submitting needs EN and FR everywhere, valid rules and the locked questions unchanged. */
  NOT_READY("health-check.not-ready", 409),
  /** The safety question and screener items are fixed by Solace. */
  LOCKED("health-check.locked", 400),
  /** Questions still to answer. */
  NOT_COMPLETE("health-check.not-complete", 409),
  /** The latest response is older than the retake window. */
  RETAKE_NEEDED("health-check.retake-needed", 409);

  private final String code;
  private final int status;

  HealthCheckError(String code, int status) {
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
    return "HCK";
  }
}
