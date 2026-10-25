package care.solace.core.admin;

import care.solace.jobs.JobAccess;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Core's jobs on the portal: the settings area, Super admins change, Operations look (D61). */
@Component
class CoreJobAccess implements JobAccess {

  private final StaffAccess access;

  CoreJobAccess(StaffAccess access) {
    this.access = access;
  }

  @Override
  public Staff require(Jwt token, boolean change) {
    var staff =
        access.require(
            token, "settings", change ? StaffAccess.Level.CHANGE : StaffAccess.Level.VIEW);
    return new Staff(staff.id(), staff.name());
  }
}
