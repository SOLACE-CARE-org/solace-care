package care.solace.core.admin;

import java.util.List;

/** The seven staff roles of the permission matrix (Admin Portal Spec, Roles and permissions). */
final class StaffRoles {

  static final List<String> ALL =
      List.of(
          "SUPER_ADMIN",
          "OPERATIONS",
          "SUPPORT",
          "FINANCE",
          "CLINICAL_REVIEWER",
          "CRISIS_RESPONDER",
          "CONTENT_EDITOR");

  /** Only these seven: Keycloak's tokens also carry its own roles, such as offline_access. */
  static List<String> only(List<String> roles) {
    return roles.stream().filter(ALL::contains).toList();
  }

  private StaffRoles() {}
}
