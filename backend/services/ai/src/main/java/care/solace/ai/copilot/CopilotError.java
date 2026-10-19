package care.solace.ai.copilot;

import care.solace.errors.ErrorCode;

/** Errors of the admin copilot; titles in messages.properties and messages_fr.properties. */
enum CopilotError implements ErrorCode {
  /** The question did not map to a metric, or no model may read questions yet: pick one instead. */
  NO_MATCH("copilot.no-match", 409);

  private final String code;
  private final int status;

  CopilotError(String code, int status) {
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
    return "COP";
  }
}
