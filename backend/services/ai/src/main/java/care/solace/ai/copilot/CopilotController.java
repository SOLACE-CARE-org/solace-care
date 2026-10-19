package care.solace.ai.copilot;

import care.solace.ai.admin.StaffGate;
import care.solace.audit.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The admin copilot (contracts/openapi/ai.yaml, tag admin-copilot; D58), for Analytics staff. */
@RestController
class CopilotController {

  record Question(@Size(max = 300) String question, String metric, String period) {}

  private final StaffGate staff;
  private final Copilot copilot;
  private final AuditLog audit;

  CopilotController(StaffGate staff, Copilot copilot, AuditLog audit) {
    this.staff = staff;
    this.copilot = copilot;
    this.audit = audit;
  }

  @PostMapping("/admin/v1/copilot/questions")
  Copilot.Answer ask(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody Question q, Locale locale) {
    staff.require(token, "analytics", false);
    var answer = copilot.ask(q.question(), q.metric(), q.period(), locale);
    // What was asked is the metric and the period; the words of the question are not kept here.
    audit.adminAction("copilot.ask", "metric", answer.metric(), Map.of("period", answer.period()));
    return answer;
  }
}
