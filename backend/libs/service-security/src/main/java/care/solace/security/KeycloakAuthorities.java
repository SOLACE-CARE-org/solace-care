package care.solace.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a Keycloak access token into Spring authorities: realm roles become {@code ROLE_SEEKER},
 * {@code ROLE_FINANCE} and so on; scopes become {@code SCOPE_tracking:summary.read}. Keycloak's own
 * lower-case roles ({@code offline_access}, {@code default-roles-...}) are left out. The principal
 * name is the pseudonymous {@code sub}.
 */
public class KeycloakAuthorities implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
  }

  static Collection<GrantedAuthority> authorities(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
      for (Object role : roles) {
        String name = String.valueOf(role);
        if (name.equals(name.toUpperCase(java.util.Locale.ROOT))) {
          authorities.add(new SimpleGrantedAuthority("ROLE_" + name));
        }
      }
    }
    String scope = jwt.getClaimAsString("scope");
    if (scope != null) {
      for (String s : scope.trim().split("\\s+")) {
        if (!s.isEmpty()) {
          authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
        }
      }
    }
    return authorities;
  }
}
