package care.solace.audit;

import care.solace.audit.AuditEntry.Actor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.flywaydb.core.api.Location;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The audit log for services with a database, plus its Flyway migration. */
@AutoConfiguration(
    after = {
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    })
@ConditionalOnClass(JdbcTemplate.class)
public class AuditAutoConfiguration {

  /** Where the audit_log migration lives; added to the service's Flyway locations. */
  public static final String MIGRATIONS = "classpath:db/audit";

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
  AuditLog auditLog(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    return new JdbcAuditLog(jdbc, transactions, currentActor(), AuditAutoConfiguration::traceId);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
  static class Migrations {
    @Bean
    FlywayConfigurationCustomizer auditMigrations() {
      return configuration -> {
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        Location audit = new Location(MIGRATIONS);
        if (!locations.contains(audit)) {
          locations.add(audit);
        }
        configuration.locations(locations.toArray(Location[]::new));
      };
    }
  }

  private static Supplier<Actor> currentActor() {
    try {
      Class.forName(
          "org.springframework.security.oauth2.jwt.Jwt",
          false,
          AuditAutoConfiguration.class.getClassLoader());
      return TokenActors::current;
    } catch (ClassNotFoundException e) {
      return () -> {
        throw new IllegalStateException("No security on the classpath; pass an Actor explicitly");
      };
    }
  }

  /** Micrometer Tracing keeps the current trace id in the MDC. */
  private static String traceId() {
    return MDC.get("traceId");
  }
}
