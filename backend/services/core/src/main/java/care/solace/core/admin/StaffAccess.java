package care.solace.core.admin;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The admin permission matrix (contracts/permissions/admin-permissions.json), enforced on the
 * server for every admin endpoint (threat-model-core.md, CO-3). The portal hides the same areas,
 * but only this check counts.
 */
public interface StaffAccess {

  /** F in the matrix is CHANGE; V is VIEW. CHANGE includes VIEW. */
  enum Level {
    VIEW,
    CHANGE
  }

  /**
   * The staff member behind the token, if their roles give {@code level} on {@code area}. Answers
   * 403 {@code access.denied} for anyone else: member tokens, service tokens, or staff without the
   * access.
   *
   * @param area an area of the matrix, e.g. {@code specialists}
   */
  Staff require(Jwt token, String area, Level level);

  /** True when the token's roles give {@code level} on {@code area}. */
  boolean allows(Jwt token, String area, Level level);

  /**
   * A staff member as audit and activity records name them.
   *
   * @param id Keycloak's subject in the staff realm
   * @param name the name staff see in activity logs ("N. Ebai")
   */
  record Staff(String id, String name, List<String> roles) {}
}
