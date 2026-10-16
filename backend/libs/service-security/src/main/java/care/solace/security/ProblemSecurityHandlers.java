package care.solace.security;

import care.solace.errors.CommonError;
import care.solace.errors.ErrorCode;
import care.solace.errors.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Answers 401 and 403 from the security filters in the same problem format as the controllers
 * (libs/error-model), with the {@code WWW-Authenticate} header a bearer-token client expects.
 */
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final Problems problems;
  private final LocaleResolver locales;
  private final ObjectMapper json;

  /**
   * @param json Spring's ObjectMapper, which writes a problem's extra fields (code, reference,
   *     traceId) at the top level
   */
  ProblemSecurityHandlers(Problems problems, LocaleResolver locales, ObjectMapper json) {
    this.problems = problems;
    this.locales = locales;
    this.json = json;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    write(request, response, CommonError.AUTH_REQUIRED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    write(request, response, CommonError.ACCESS_DENIED);
  }

  private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode error)
      throws IOException {
    ProblemDetail problem = problems.of(error, locales.resolveLocale(request));
    response.setStatus(error.status());
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), problem);
  }
}
