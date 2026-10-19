package care.solace.ai.admin;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * A member behind a members-realm token. Core makes the account id the Keycloak username, so {@code
 * preferred_username} is the pseudonymous id the other services use (D49).
 */
@Component
public class Members {

  private final String membersIssuer;

  Members(
      @Value("${solace.ai.members-issuer:http://localhost:8180/realms/solace-members}")
          String membersIssuer) {
    this.membersIssuer = membersIssuer;
  }

  public UUID specialist(Jwt token) {
    return member(token, "SPECIALIST");
  }

  /** A registered seeker; guests have no members-realm token. */
  public UUID seeker(Jwt token) {
    return member(token, "SEEKER");
  }

  private UUID member(Jwt token, String role) {
    if (token == null
        || token.getIssuer() == null
        || !membersIssuer.equals(token.getIssuer().toString())
        || !hasRole(token, role)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    try {
      return UUID.fromString(token.getClaimAsString("preferred_username"));
    } catch (RuntimeException e) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
  }

  private static boolean hasRole(Jwt token, String role) {
    Map<String, Object> access = token.getClaimAsMap("realm_access");
    return access != null
        && access.get("roles") instanceof Collection<?> roles
        && roles.contains(role);
  }
}
