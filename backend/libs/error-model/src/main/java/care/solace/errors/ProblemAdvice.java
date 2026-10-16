package care.solace.errors;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every exception that leaves a controller into a problem response. Unexpected exceptions
 * become {@link CommonError#INTERNAL}; their message never reaches the client.
 */
@RestControllerAdvice
public class ProblemAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

  private final Problems problems;

  public ProblemAdvice(Problems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(SolaceException.class)
  ResponseEntity<ProblemDetail> solace(SolaceException e) {
    if (e.error().status() >= 500) {
      LOG.error("Request failed: errorCode={}", e.error().code(), e);
    }
    return respond(problems.of(e.error(), locale(), e.titleArguments()));
  }

  /** Bean validation: names the fields that failed, never their values. */
  @ExceptionHandler(BindException.class)
  ResponseEntity<ProblemDetail> invalid(BindException e) {
    ProblemDetail problem = problems.of(CommonError.REQUEST_INVALID, locale());
    List<String> fields =
        e.getFieldErrors().stream().map(error -> error.getField()).distinct().sorted().toList();
    problem.setProperty("fields", fields);
    return respond(problem);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    // Framework exceptions that already carry a status: 404, 405, 415, missing parameters.
    if (e instanceof ErrorResponse framework) {
      CommonError mapped = CommonError.forStatus(framework.getStatusCode().value());
      return respond(problems.of(mapped, locale()));
    }
    CommonError mapped = SecurityErrors.map(e);
    if (mapped != null) {
      return respond(problems.of(mapped, locale()));
    }
    LOG.error("Unexpected error: errorCode={}", CommonError.INTERNAL.code(), e);
    return respond(problems.of(CommonError.INTERNAL, locale()));
  }

  private static Locale locale() {
    return LocaleContextHolder.getLocale();
  }

  private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus())
        .header("Content-Type", "application/problem+json")
        .body(problem);
  }
}
