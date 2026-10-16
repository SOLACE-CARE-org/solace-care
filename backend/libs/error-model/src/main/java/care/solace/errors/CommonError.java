package care.solace.errors;

import java.util.Arrays;

/** Errors every service can raise, and the mapping from plain HTTP statuses. */
public enum CommonError implements ErrorCode {
  REQUEST_INVALID("request.invalid", 400, "REQ"),
  AUTH_REQUIRED("auth.required", 401, "AUTH"),
  ACCESS_DENIED("access.denied", 403, "AUTH"),
  NOT_FOUND("resource.not-found", 404, "REQ"),
  CONFLICT("resource.conflict", 409, "REQ"),
  RATE_LIMITED("rate-limited", 429, "REQ"),
  INTERNAL("internal", 500, "SYS"),
  DEPENDENCY_UNAVAILABLE("dependency.unavailable", 503, "SYS");

  private final String code;
  private final int status;
  private final String area;

  CommonError(String code, int status, String area) {
    this.code = code;
    this.status = status;
    this.area = area;
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
    return area;
  }

  /**
   * The common error for an HTTP status raised by the framework (405, 415 and other 4xx become
   * {@link #REQUEST_INVALID}; other 5xx become {@link #INTERNAL}).
   */
  public static CommonError forStatus(int status) {
    return Arrays.stream(values())
        .filter(e -> e.status == status)
        .findFirst()
        .orElse(status >= 500 ? INTERNAL : REQUEST_INVALID);
  }
}
