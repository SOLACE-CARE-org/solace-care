package care.solace.tracking.modules;

import care.solace.lists.ListAccess;
import care.solace.lists.ReviewedLists;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Tracking's reviewed lists on the portal (D61): anyone who sees the module builder looks; the list
 * names who drafts and who signs off.
 */
@Component
class TrackingListAccess implements ListAccess {

  private final StaffGate gate;

  TrackingListAccess(StaffGate gate) {
    this.gate = gate;
  }

  @Override
  public ReviewedLists.Staff require(Jwt token) {
    var staff = gate.require(token, "trackingModules", false);
    return new ReviewedLists.Staff(staff.id(), staff.name(), staff.roles());
  }
}
