package care.solace.tracking.cycles;

import care.solace.audit.AuditLog;
import care.solace.tracking.Seekers;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The cycle calendar and summary (tag cycles; D50): facts only, never a forecast. */
@RestController
class CycleController {

  private final Seekers seekers;
  private final CycleReconciler cycles;
  private final AuditLog audit;

  CycleController(Seekers seekers, CycleReconciler cycles, AuditLog audit) {
    this.seekers = seekers;
    this.cycles = cycles;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CycleSummary(List<CycleModel.Cycle> cycles, Integer cycleDay) {}

  @GetMapping("/v1/tracking/cycles")
  CycleSummary cycles(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(defaultValue = "Africa/Douala") String timeZone) {
    UUID user = seekers.require(token);
    ZoneId zone;
    try {
      zone = ZoneId.of(timeZone);
    } catch (DateTimeException e) {
      zone = ZoneId.of("Africa/Douala");
    }
    audit.read("tracking.cycles.read", "cycles", null, user.toString());
    return new CycleSummary(cycles.cycles(user), cycles.cycleDay(user, zone).orElse(null));
  }
}
