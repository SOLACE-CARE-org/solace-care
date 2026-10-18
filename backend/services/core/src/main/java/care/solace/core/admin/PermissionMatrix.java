package care.solace.core.admin;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@link StaffAccess} from the shared matrix on the classpath (the contracts module ships it). Only
 * a person's token from the staff realm counts: members cannot hold staff roles, and service tokens
 * (client credentials) are not people.
 */
@Component
class PermissionMatrix implements StaffAccess {

  static final String RESOURCE = "/admin-permissions.json";

  private final String staffIssuer;
  private final StaffDirectory directory;
  private final Map<String, Map<String, Level>> areas = new HashMap<>();

  PermissionMatrix(
      @Value("${solace.security.service-issuer:http://localhost:8180/realms/solace-staff}")
          String staffIssuer,
      StaffDirectory directory) {
    this.staffIssuer = staffIssuer;
    this.directory = directory;
    try (InputStream in = PermissionMatrix.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + RESOURCE + " (contracts/permissions)");
      }
      JsonNode root = new ObjectMapper().readTree(in);
      root.path("areas")
          .properties()
          .forEach(
              area -> {
                Map<String, Level> roles = new HashMap<>();
                area.getValue()
                    .properties()
                    .forEach(
                        role ->
                            roles.put(
                                role.getKey(),
                                "F".equals(role.getValue().asText()) ? Level.CHANGE : Level.VIEW));
                areas.put(area.getKey(), Map.copyOf(roles));
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Staff require(Jwt token, String area, Level level) {
    if (!allows(token, area, level)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    String name = token.getClaimAsString("name");
    if (name == null || name.isBlank()) {
      name = token.getClaimAsString("preferred_username");
    }
    var staff =
        new Staff(token.getSubject(), name == null ? token.getSubject() : name, roles(token));
    // Sessions end on a role change, a new password or deactivation (D7, D42).
    if (!directory.admit(staff.id(), staff.name(), staff.roles(), token.getIssuedAt())) {
      throw new SolaceException(CommonError.AUTH_REQUIRED);
    }
    return staff;
  }

  @Override
  public boolean allows(Jwt token, String area, Level level) {
    if (!isStaffPerson(token)) {
      return false;
    }
    Map<String, Level> row = areas.get(area);
    if (row == null) {
      throw new IllegalArgumentException("Unknown admin area " + area);
    }
    for (String role : roles(token)) {
      Level granted = row.get(role);
      if (granted == Level.CHANGE || (granted == Level.VIEW && level == Level.VIEW)) {
        return true;
      }
    }
    return false;
  }

  private boolean isStaffPerson(Jwt token) {
    return token != null
        && token.getIssuer() != null
        && staffIssuer.equals(token.getIssuer().toString())
        && token.getClaimAsString("client_id") == null;
  }

  private static List<String> roles(Jwt token) {
    Map<String, Object> realmAccess = token.getClaimAsMap("realm_access");
    if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
      return roles.stream().map(String::valueOf).toList();
    }
    return List.of();
  }
}
