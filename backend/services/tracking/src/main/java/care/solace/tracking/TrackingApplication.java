package care.solace.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tracking service (docs/specs/tracking-service-spec.md): elements, modules, rules and
 * observations, in its own database. It states facts and never interprets them (D10).
 */
@SpringBootApplication
public class TrackingApplication {

  public static void main(String[] args) {
    SpringApplication.run(TrackingApplication.class, args);
  }
}
