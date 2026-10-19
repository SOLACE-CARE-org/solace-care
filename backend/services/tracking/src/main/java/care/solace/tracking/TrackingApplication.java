package care.solace.tracking;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tracking service (docs/specs/tracking-service-spec.md): elements, modules, rules and
 * observations, in its own database. It states facts and never interprets them (D10).
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class TrackingApplication {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  public static void main(String[] args) {
    SpringApplication.run(TrackingApplication.class, args);
  }
}
