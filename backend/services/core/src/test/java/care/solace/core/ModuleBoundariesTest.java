package care.solace.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Core's modules may only use each other through their published APIs (D1). */
class ModuleBoundariesTest {

  private final ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

  @Test
  void modulesRespectTheirBoundaries() {
    modules.verify();
  }

  @Test
  void coreHasTheModulesFromTheSystemDesign() {
    assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
        .containsExactlyInAnyOrder(
            "identity",
            "guests",
            "specialists",
            "catalog",
            "healthcheck",
            "scheduling",
            "bookings",
            "sessions",
            "payments",
            "reviews",
            "notifications",
            // Notifications split in two (D38): delivery, and messaging, which decides what to send.
            "messaging",
            "content",
            "admin",
            "auditlog",
            // Implementation plan, Phase 1 epic 11: consent records, export, erasure, retention.
            "privacy",
            "system");
  }
}
