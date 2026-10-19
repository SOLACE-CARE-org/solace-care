package care.solace.ai.features;

import care.solace.errors.ErrorCode;

/** Errors of AI controls; titles in messages.properties and messages_fr.properties. */
public enum FeatureError implements ErrorCode {
  /** Switching on or promoting needs a passing evaluation in both languages. */
  NOT_EVALUATED("ai.not-evaluated", 409),
  /** Only a Clinical reviewer signs off a prompt version. */
  SIGN_OFF_NEEDED("ai.sign-off-needed", 403);

  private final String code;
  private final int status;

  FeatureError(String code, int status) {
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
    return "AIC";
  }
}
