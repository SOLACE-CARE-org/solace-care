package care.solace.ai.admin;

import care.solace.lists.ListAccess;
import care.solace.lists.ReviewedLists;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * AI's reviewed lists on the portal (D61): anyone who sees AI controls looks; each list names who
 * drafts and who signs off, Clinical reviewers for all of them.
 */
@Component
class AiListAccess implements ListAccess {

  private final StaffGate gate;

  AiListAccess(StaffGate gate) {
    this.gate = gate;
  }

  @Override
  public ReviewedLists.Staff require(Jwt token) {
    var staff = gate.require(token, "aiControls", false);
    return new ReviewedLists.Staff(staff.id(), staff.name(), staff.roles());
  }
}
