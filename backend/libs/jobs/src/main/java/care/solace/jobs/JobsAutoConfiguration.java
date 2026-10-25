package care.solace.jobs;

import care.solace.audit.AuditLog;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Jobs from each service's {@code job_trigger} table (D61). The service's own migration creates the
 * table; its {@link JobAccess} bean turns on the admin API.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
public class JobsAutoConfiguration {

  @Bean
  static JobTriggers.Collector jobCollector() {
    return new JobTriggers.Collector();
  }

  @Bean
  JobTriggerStore jobTriggerStore(JdbcTemplate jdbc) {
    return new JobTriggerStore(jdbc);
  }

  @Bean
  JobTriggers jobTriggers(
      JobTriggers.Collector collector,
      JobTriggerStore store,
      ObjectProvider<Clock> clock,
      Environment environment) {
    return new JobTriggers(
        collector,
        store,
        clock.getIfAvailable(Clock::systemUTC),
        environment.getProperty("solace.jobs.schedule-from-code", Boolean.class, false));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean({JobAccess.class, AuditLog.class})
  static class Admin {
    @Bean
    JobsController jobsController(
        JobAccess access, JobTriggerStore store, AuditLog audit, ObjectProvider<Clock> clock) {
      return new JobsController(access, store, audit, clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
