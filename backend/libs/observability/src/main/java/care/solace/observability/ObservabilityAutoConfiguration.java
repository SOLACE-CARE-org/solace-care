package care.solace.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Connects the OpenTelemetry log appender (declared in care/solace/observability/logback.xml) to
 * Spring Boot's OpenTelemetry SDK, so scrubbed log lines go to Loki with their trace and span ids.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration",
    afterName =
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration")
public class ObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnBean(OpenTelemetry.class)
  InitializingBean solaceOpenTelemetryLogs(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }

  /**
   * Async work keeps the trace of the code that started it. Spring Boot applies this to its task
   * executor, which the outbox uses to send events to Kafka after the commit, so the Kafka send and
   * its consumers join the request's trace instead of starting a new one.
   */
  @Bean
  @ConditionalOnMissingBean(TaskDecorator.class)
  ContextPropagatingTaskDecorator solaceContextPropagatingTaskDecorator() {
    return new ContextPropagatingTaskDecorator();
  }
}
