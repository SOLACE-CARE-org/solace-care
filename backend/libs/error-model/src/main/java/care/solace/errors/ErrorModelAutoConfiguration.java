package care.solace.errors;

import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/** Problem responses for servlet services. */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class ErrorModelAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Problems problems(ObjectProvider<MessageSource> messageSource) {
    return new Problems(messageSource.getIfAvailable(), currentTraceId());
  }

  @Bean
  @ConditionalOnMissingBean
  ProblemAdvice problemAdvice(Problems problems) {
    return new ProblemAdvice(problems);
  }

  /**
   * The current trace id: from OpenTelemetry when it is on the classpath, otherwise from the {@code
   * traceId} MDC entry that Micrometer Tracing maintains.
   */
  static Supplier<String> currentTraceId() {
    boolean otel = isPresent("io.opentelemetry.api.trace.Span");
    return () -> {
      if (otel) {
        String fromSpan = OpenTelemetryTrace.currentTraceId();
        if (fromSpan != null) {
          return fromSpan;
        }
      }
      return MDC.get("traceId");
    };
  }

  private static boolean isPresent(String className) {
    try {
      Class.forName(className, false, ErrorModelAutoConfiguration.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  static final class OpenTelemetryTrace {
    private OpenTelemetryTrace() {}

    static String currentTraceId() {
      io.opentelemetry.api.trace.SpanContext context =
          io.opentelemetry.api.trace.Span.current().getSpanContext();
      return context.isValid() ? context.getTraceId() : null;
    }
  }
}
