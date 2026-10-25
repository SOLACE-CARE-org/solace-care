package care.solace.ai.admin;

import care.solace.jobs.JobAccess;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** AI's jobs on the portal: the settings area, Super admins change, Operations look (D61). */
@Component
class AiJobAccess implements JobAccess {

  private final StaffGate gate;

  AiJobAccess(StaffGate gate) {
    this.gate = gate;
  }

  @Override
  public Staff require(Jwt token, boolean change) {
    var staff = gate.require(token, "settings", change);
    return new Staff(staff.id(), staff.name());
  }
}
