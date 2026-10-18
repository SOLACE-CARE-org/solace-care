package care.solace.core.healthcheck;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The launch version, v1.0, arrives as a draft when there is no version at all (implementation
 * plan, Phase 2, epic 11; D55). Two Clinical reviewers write and approve it before it is published;
 * nothing reaches seekers before that.
 */
@Component
class HealthCheckSeed implements ApplicationRunner {

  private final Versions versions;

  HealthCheckSeed(Versions versions) {
    this.versions = versions;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (versions.all().isEmpty()) {
      try {
        versions.seed("launch-seed", "Launch seed");
      } catch (org.springframework.dao.DuplicateKeyException raced) {
        // Another instance seeded it first.
      }
    }
  }
}
