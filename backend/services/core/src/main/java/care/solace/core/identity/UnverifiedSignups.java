package care.solace.core.identity;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes accounts whose first sign-in never came, after {@code
 * solace.identity.unverified-signup-ttl}, so a mistyped or someone else's number does not keep an
 * account. Runs on every replica; deleting is idempotent, so overlaps are harmless.
 */
@Component
class UnverifiedSignups {

  private static final Logger LOG = LoggerFactory.getLogger(UnverifiedSignups.class);
  private static final int BATCH = 100;

  private final AccountStore store;
  private final KeycloakMembers keycloak;
  private final IdentitySettings settings;
  private final Clock clock;

  UnverifiedSignups(
      AccountStore store, KeycloakMembers keycloak, IdentitySettings settings, Clock clock) {
    this.store = store;
    this.keycloak = keycloak;
    this.settings = settings;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${solace.identity.unverified-cleanup-interval:PT15M}",
      initialDelayString = "PT1M")
  void removeExpired() {
    var cutoff = clock.instant().minus(settings.unverifiedSignupTtl());
    int removed = 0;
    for (var row : store.pendingBefore(cutoff, BATCH)) {
      try {
        keycloak.delete(row.keycloakId());
        store.deletePending(row.id());
        removed++;
      } catch (RuntimeException e) {
        LOG.warn("Could not remove unverified account {}", row.id(), e);
      }
    }
    if (removed > 0) {
      LOG.info("Removed {} unverified account(s)", removed);
    }
  }
}
