package care.solace.jobs;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who may see and change this service's jobs: each service checks its own staff rules (the
 * "settings" area of the permission matrix: Super admins change, Operations look).
 */
public interface JobAccess {

  /** The staff member behind the token; answers 403 when they may not. */
  Staff require(Jwt token, boolean change);

  record Staff(String id, String name) {}
}
