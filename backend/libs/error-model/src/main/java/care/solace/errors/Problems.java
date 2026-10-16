package care.solace.errors;

import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem details:
 *
 * <pre>{@code
 * { "type": "urn:solace:error:booking.slot-taken", "title": "...", "status": 409,
 *   "code": "booking.slot-taken", "reference": "BKG-409", "traceId": "4bf9..." }
 * }</pre>
 *
 * Titles come from the service's messages first, then this library's EN/FR defaults.
 */
public class Problems {

  static final String TYPE_PREFIX = "urn:solace:error:";

  private final MessageSource serviceMessages;
  private final ResourceBundleMessageSource defaults;
  private final Supplier<String> traceId;

  public Problems(MessageSource serviceMessages, Supplier<String> traceId) {
    this.serviceMessages = serviceMessages;
    this.traceId = traceId;
    this.defaults = new ResourceBundleMessageSource();
    defaults.setBasename("care/solace/errors/messages");
    defaults.setDefaultEncoding("UTF-8");
    defaults.setFallbackToSystemLocale(false);
  }

  public ProblemDetail of(ErrorCode error, Locale locale, Object... titleArguments) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(error.status()));
    problem.setType(URI.create(TYPE_PREFIX + error.code()));
    problem.setTitle(title(error, locale, titleArguments));
    problem.setProperty("code", error.code());
    problem.setProperty("reference", error.reference(error.status()));
    String trace = traceId.get();
    if (trace != null && !trace.isBlank()) {
      problem.setProperty("traceId", trace);
    }
    return problem;
  }

  private String title(ErrorCode error, Locale locale, Object[] arguments) {
    String fallback = defaults.getMessage(error.titleKey(), arguments, error.code(), locale);
    if (serviceMessages == null) {
      return fallback;
    }
    return serviceMessages.getMessage(error.titleKey(), arguments, fallback, locale);
  }
}
