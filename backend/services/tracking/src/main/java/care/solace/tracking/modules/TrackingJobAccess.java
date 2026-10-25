package care.solace.tracking.modules;

import care.solace.jobs.JobAccess;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Tracking's jobs on the portal: the settings area, Super admins change, Operations look (D61). */
@Component
class TrackingJobAccess implements JobAccess {

  private final StaffGate gate;

  TrackingJobAccess(StaffGate gate) {
    this.gate = gate;
  }

  @Override
  public Staff require(Jwt token, boolean change) {
    var staff = gate.require(token, "settings", change);
    return new Staff(staff.id(), staff.name());
  }
}
