package care.solace.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Connects the OpenTelemetry log appender (declared in care/solace/observability/logback.xml) to
 * Spring Boot's OpenTelemetry SDK, so scrubbed log lines go to Loki with their trace and span ids.
 */
@AutoConfiguration(
    afterName =
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration")
public class ObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnBean(OpenTelemetry.class)
  InitializingBean solaceOpenTelemetryLogs(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }
}
