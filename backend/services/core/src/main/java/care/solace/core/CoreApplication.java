package care.solace.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Core service (System Design, Core service). One deployable and one transaction boundary for
 * booking and payment; each sub-package is a Spring Modulith application module whose boundaries
 * are checked in tests, so any module can be split out later.
 */
@SpringBootApplication
@Modulithic(systemName = "Solace Core")
public class CoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreApplication.class, args);
  }
}
