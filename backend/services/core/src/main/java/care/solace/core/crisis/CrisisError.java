package care.solace.core.crisis;

import care.solace.errors.ErrorCode;

/** Errors of the crisis queue; titles in messages.properties and messages_fr.properties. */
enum CrisisError implements ErrorCode {
  /** The alert is already acknowledged, or closed. */
  WRONG_STATE("crisis.wrong-state", 409),
  /** Shifts overlap, end before they start, or name no one. */
  ROTA_INVALID("crisis.rota-invalid", 400);

  private final String code;
  private final int status;

  CrisisError(String code, int status) {
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
    return "CRI";
  }
}
