package care.solace.tracking;

import care.solace.errors.SolaceException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The seeker behind a members-realm token. Core makes the account id the Keycloak username, so
 * {@code preferred_username} is the same pseudonymous id events carry (D49). Tracking stores no
 * names, phones or emails.
 */
@Component
public class Seekers {

  private final String membersIssuer;

  Seekers(
      @Value("${solace.tracking.members-issuer:http://localhost:8180/realms/solace-members}")
          String membersIssuer) {
    this.membersIssuer = membersIssuer;
  }

  /** The seeker's id; 403 tracking.seekers-only for anyone else. */
  public UUID require(Jwt token) {
    if (token == null
        || token.getIssuer() == null
        || !membersIssuer.equals(token.getIssuer().toString())
        || !hasRole(token, "SEEKER")) {
      throw new SolaceException(TrackingError.SEEKERS_ONLY);
    }
    return id(token);
  }

  /** A specialist's id, for reads of shared data. */
  public UUID requireSpecialist(Jwt token) {
    if (token == null
        || token.getIssuer() == null
        || !membersIssuer.equals(token.getIssuer().toString())
        || !hasRole(token, "SPECIALIST")) {
      throw new SolaceException(TrackingError.NO_ACCESS);
    }
    return id(token);
  }

  private static UUID id(Jwt token) {
    try {
      return UUID.fromString(token.getClaimAsString("preferred_username"));
    } catch (RuntimeException e) {
      throw new SolaceException(TrackingError.SEEKERS_ONLY);
    }
  }

  static boolean hasRole(Jwt token, String role) {
    Map<String, Object> access = token.getClaimAsMap("realm_access");
    return access != null
        && access.get("roles") instanceof Collection<?> roles
        && roles.contains(role);
  }
}
