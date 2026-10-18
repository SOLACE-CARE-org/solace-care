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
            // Notifications split in two (D38): delivery, and messaging, which decides what to
            // send.
            "messaging",
            // The crisis queue and the on-call rota (epic 10, D39).
            "crisis",
            "content",
            "admin",
            "auditlog",
            // Implementation plan, Phase 1 epic 11: consent records, export, erasure, retention.
            "privacy",
            // The live overview and analytics, read-only aggregates (epic 12, D46).
            "insight",
            // Tracking invites with matched specialists (Phase 2, epic 6, D52).
            "invites",
            "system");
  }
}
