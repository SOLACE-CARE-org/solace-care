package care.solace.lists;

import care.solace.errors.ErrorCode;

/** Errors of the reviewed lists (D61); titles in each service's messages properties. */
enum ListError implements ErrorCode {
  /** The entries are not what the list can use. */
  INVALID("list.invalid", 400),
  /** Only a draft can be signed off or declined. */
  NOT_DRAFT("list.not-draft", 409),
  /** The person who drafted a version never decides it. */
  SAME_PERSON("list.same-person", 403),
  /** The list's check did not pass, such as the safety set's recall. */
  CHECK_FAILED("list.check-failed", 409);

  private final String code;
  private final int status;

  ListError(String code, int status) {
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
    return "LST";
  }
}
