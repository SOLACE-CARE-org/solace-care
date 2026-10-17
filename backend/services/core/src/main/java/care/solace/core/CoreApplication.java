package care.solace.core;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Core service (System Design, Core service). One deployable and one transaction boundary for
 * booking and payment; each sub-package is a Spring Modulith application module whose boundaries
 * are checked in tests, so any module can be split out later.
 */
@SpringBootApplication
@Modulithic(systemName = "Solace Core")
@EnableScheduling
@EnableMethodSecurity
public class CoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreApplication.class, args);
  }

  /** Every module reads the time from here, so tests can fix it. Times are UTC. */
  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }
}
