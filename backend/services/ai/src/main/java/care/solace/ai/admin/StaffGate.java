package care.solace.ai.admin;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The admin permission matrix (contracts/permissions/admin-permissions.json) for AI controls: Super
 * admins change; Operations and Clinical reviewers look, and a Clinical reviewer signs off a prompt
 * version's graded sample (admin screen 26; D56). Only a staff person's token counts.
 */
@Component
public class StaffGate {

  public record Staff(String id, String name, List<String> roles) {

    public boolean clinicalReviewer() {
      return roles.contains("CLINICAL_REVIEWER");
    }
  }

  private final String staffIssuer;
  private final JsonNode area;

  StaffGate(
      @Value("${solace.security.service-issuer:http://localhost:8180/realms/solace-staff}")
          String staffIssuer) {
    this.staffIssuer = staffIssuer;
    try (InputStream in = StaffGate.class.getResourceAsStream("/admin-permissions.json")) {
      if (in == null) {
        throw new IllegalStateException("Missing /admin-permissions.json (contracts/permissions)");
      }
      this.area = new ObjectMapper().readTree(in).path("areas").path("aiControls");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * @param change true for F access, false for V or F
   */
  public Staff require(Jwt token, boolean change) {
    if (token == null
        || token.getIssuer() == null
        || !staffIssuer.equals(token.getIssuer().toString())
        || token.getClaimAsString("client_id") != null) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    List<String> roles = roles(token);
    boolean allowed =
        roles.stream()
            .map(r -> area.path(r).asText(""))
            .anyMatch(level -> level.equals("F") || (!change && level.equals("V")));
    if (!allowed) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    String name = token.getClaimAsString("name");
    return new Staff(token.getSubject(), name == null ? token.getSubject() : name, roles);
  }

  private static List<String> roles(Jwt token) {
    Map<String, Object> access = token.getClaimAsMap("realm_access");
    if (access != null && access.get("roles") instanceof Collection<?> roles) {
      return roles.stream().map(String::valueOf).toList();
    }
    return List.of();
  }
}
