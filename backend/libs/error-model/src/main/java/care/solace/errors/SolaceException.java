package care.solace.errors;

import java.util.Objects;

/**
 * Thrown to answer a request with a problem response for {@link #error()}.
 *
 * <p>The exception message is only the error code, because it ends up in logs; put ids, never user
 * data, in the title arguments.
 */
public class SolaceException extends RuntimeException {

  private final ErrorCode error;
  private final transient Object[] titleArguments;

  public SolaceException(ErrorCode error, Object... titleArguments) {
    this(error, null, titleArguments);
  }

  public SolaceException(ErrorCode error, Throwable cause, Object... titleArguments) {
    super(Objects.requireNonNull(error).code(), cause);
    this.error = error;
    this.titleArguments = titleArguments.clone();
  }

  public ErrorCode error() {
    return error;
  }

  public Object[] titleArguments() {
    return titleArguments.clone();
  }
}
