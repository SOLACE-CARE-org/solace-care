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

  private StaffRoles() {}
}
