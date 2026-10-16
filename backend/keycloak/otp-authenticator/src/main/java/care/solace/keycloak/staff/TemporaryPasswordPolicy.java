package care.solace.keycloak.staff;

import java.time.Duration;
import java.time.Instant;

/**
 * A staff temporary password works once and expires 72 hours after it is set (D7). The 72 hours is
 * a decision, not a placeholder.
 *
 * <p>"Works once" comes from Keycloak: the password is set with the Update Password required
 * action, so the first sign-in replaces it. Keycloak has no expiry for it, which is what this adds.
 */
public final class TemporaryPasswordPolicy {

  public static final Duration VALIDITY = Duration.ofHours(72);

  private TemporaryPasswordPolicy() {}

  /**
   * Whether a sign-in with this password must be refused.
   *
   * @param temporary the account still has to replace its password (Update Password pending)
   * @param passwordSetAt when the current password was set; {@code null} if unknown
   */
  public static boolean isExpired(boolean temporary, Instant passwordSetAt, Instant now) {
    if (!temporary) {
      return false;
    }
    if (passwordSetAt == null) {
      // No creation time means we cannot prove the password is fresh: refuse.
      return true;
    }
    return !passwordSetAt.plus(VALIDITY).isAfter(now);
  }
}
