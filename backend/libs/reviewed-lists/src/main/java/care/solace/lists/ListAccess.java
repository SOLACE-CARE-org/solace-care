package care.solace.lists;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who may see this service's reviewed lists; each service checks its own staff rules. Drafting and
 * signing off are then decided by the roles each list names.
 */
public interface ListAccess {

  /** The staff member behind the token, with their roles; answers 403 when they may not look. */
  ReviewedLists.Staff require(Jwt token);
}
