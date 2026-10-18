package care.solace.core.admin;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess.Level;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Team and roles (admin screen 19; tag admin-team). Super admins only; every change audited. */
@RestController
class TeamController {

  private static final String AREA = "team";
  private static final String ROLE =
      "SUPER_ADMIN|OPERATIONS|SUPPORT|FINANCE|CLINICAL_REVIEWER|CRISIS_RESPONDER|CONTENT_EDITOR";

  private final StaffAccess access;
  private final Team team;
  private final AuditLog audit;

  TeamController(StaffAccess access, Team team, AuditLog audit) {
    this.access = access;
    this.team = team;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record MemberView(
      String id,
      String name,
      String email,
      List<String> roles,
      boolean secondFactor,
      String status,
      Instant lastActiveAt,
      Instant addedAt) {}

  record PasswordView(MemberView member, String temporaryPassword, Instant expiresAt) {}

  record NewMember(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Email @Size(max = 254) String email,
      @NotEmpty List<@Pattern(regexp = ROLE) String> roles) {}

  record Roles(
      @NotEmpty List<@Pattern(regexp = ROLE) String> roles,
      @NotBlank @Size(max = 500) String reason) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/admin/v1/staff")
  List<MemberView> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return team.members().stream().map(this::view).toList();
  }

  @PostMapping("/admin/v1/staff")
  @ResponseStatus(HttpStatus.CREATED)
  PasswordView add(@AuthenticationPrincipal Jwt token, @Valid @RequestBody NewMember request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var roles = request.roles().stream().distinct().toList();
    var created = team.add(request.name(), request.email(), roles, staff);
    audit.adminAction(
        "team.staff.add",
        "staff",
        created.member().id(),
        null,
        Map.of("roles", String.join(" ", roles)));
    return view(created);
  }

  @PutMapping("/admin/v1/staff/{staffId}/roles")
  MemberView roles(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String staffId,
      @Valid @RequestBody Roles request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var roles = request.roles().stream().distinct().toList();
    var member = team.changeRoles(staffId, roles, staff);
    audit.adminAction(
        "team.staff.roles",
        "staff",
        staffId,
        request.reason().trim(),
        Map.of("roles", String.join(" ", roles)));
    return view(member);
  }

  @PostMapping("/admin/v1/staff/{staffId}/temporary-password")
  PasswordView password(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String staffId,
      @Valid @RequestBody Reason request) {
    access.require(token, AREA, Level.CHANGE);
    var reset = team.newPassword(staffId);
    audit.adminAction("team.staff.password", "staff", staffId, request.reason().trim(), Map.of());
    return view(reset);
  }

  @PostMapping("/admin/v1/staff/{staffId}/deactivation")
  MemberView deactivate(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String staffId,
      @Valid @RequestBody Reason request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var member = team.deactivate(staffId, staff);
    audit.adminAction("team.staff.deactivate", "staff", staffId, request.reason().trim(), Map.of());
    return view(member);
  }

  private PasswordView view(Team.Password p) {
    return new PasswordView(view(p.member()), p.temporaryPassword(), p.expiresAt());
  }

  private MemberView view(StaffDirectory.Member m) {
    return new MemberView(
        m.id(),
        m.name(),
        m.email() == null ? "" : m.email(),
        m.roles(),
        !m.status().equals("DEACTIVATED") && team.hasSecondFactor(m.id()),
        m.status(),
        m.lastActiveAt(),
        m.addedAt());
  }
}
