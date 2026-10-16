package care.solace.audit;

import care.solace.audit.AuditEntry.Actor;
import care.solace.audit.AuditEntry.ActorType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Works out the actor from a Keycloak access token (D23):
 *
 * <ul>
 *   <li>{@code solace-staff} realm, client-credentials token: {@code SERVICE}, id = client id;
 *   <li>{@code solace-staff} realm, person: {@code STAFF};
 *   <li>{@code solace-members} realm: {@code SPECIALIST} if the token has that role, else {@code
 *       SEEKER};
 *   <li>a Core guest token (claim {@code solace_guest=true}): {@code GUEST}.
 * </ul>
 *
 * People are identified by the token's {@code sub}, which is pseudonymous.
 */
public final class TokenActors {

  static final String STAFF_REALM = "/realms/solace-staff";
  static final String GUEST_CLAIM = "solace_guest";

  private TokenActors() {}

  /** The actor for the current security context; fails if nobody is signed in. */
  public static Actor current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new IllegalStateException(
          "No signed-in actor; jobs and consumers must pass Actor.system(...) explicitly");
    }
    return from(jwt);
  }

  public static Actor from(Jwt jwt) {
    List<String> roles = realmRoles(jwt);
    String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
    if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(GUEST_CLAIM))) {
      return new Actor(ActorType.GUEST, jwt.getSubject(), roles);
    }
    if (issuer.endsWith(STAFF_REALM)) {
      String clientId = jwt.getClaimAsString("client_id");
      if (clientId != null) {
        return new Actor(ActorType.SERVICE, clientId, roles);
      }
      return new Actor(ActorType.STAFF, jwt.getSubject(), roles);
    }
    ActorType type = roles.contains("SPECIALIST") ? ActorType.SPECIALIST : ActorType.SEEKER;
    return new Actor(type, jwt.getSubject(), roles);
  }

  private static List<String> realmRoles(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
      return roles.stream()
          .map(String::valueOf)
          .filter(role -> role.equals(role.toUpperCase(java.util.Locale.ROOT)))
          .sorted()
          .toList();
    }
    return List.of();
  }
}
