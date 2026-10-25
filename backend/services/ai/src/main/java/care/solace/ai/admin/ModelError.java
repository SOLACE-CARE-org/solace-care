package care.solace.ai.admin;

import care.solace.errors.ErrorCode;

/** Errors of managing AI models and routes (D61); titles in messages.properties and _fr. */
enum ModelError implements ErrorCode {
  /** A model with this name exists. */
  EXISTS("ai-model.exists", 409),
  /** The model's version is not the one read. */
  CHANGED("ai-model.changed", 409),
  /** A bad address, or a model name that is not one. */
  INVALID("ai-model.invalid", 400),
  /** ACTIVE without a key, for a model Solace does not host. */
  NOT_READY("ai-model.not-ready", 409),
  /** Disabled while a live or pending route still uses it. */
  IN_ROUTE("ai-model.in-route", 409),
  /** A route naming a model that is not an active chat model, or one twice. */
  ROUTE_INVALID("ai-route.invalid", 400),
  /** Nothing pending to evaluate or promote. */
  NO_PENDING("ai-route.no-pending", 409),
  /** The pending route has no passing evaluation on the live version. */
  NOT_EVALUATED("ai-route.not-evaluated", 409),
  /** The feature has no live prompt version to evaluate the route with. */
  NO_LIVE_VERSION("ai-route.no-live-version", 409);

  private final String code;
  private final int status;

  ModelError(String code, int status) {
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
    return "AIM";
  }
}
