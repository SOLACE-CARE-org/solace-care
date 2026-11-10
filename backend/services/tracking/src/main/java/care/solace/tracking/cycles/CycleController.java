package care.solace.tracking.cycles;

import care.solace.audit.AuditLog;
import care.solace.tracking.Seekers;
import care.solace.tracking.episodes.Episode;
import care.solace.tracking.episodes.EpisodeReconciler;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cycle calendar and summary (tag cycles; D50): facts only, never a forecast. Read from the
 * {@code menstrual_cycle} episode model (D61); the module presentation of Phase 2b step 4 lets the
 * app show any model's current day instead.
 */
@RestController
class CycleController {

  static final String MODEL = "menstrual_cycle";

  private final Seekers seekers;
  private final EpisodeReconciler episodes;
  private final AuditLog audit;

  CycleController(Seekers seekers, EpisodeReconciler episodes, AuditLog audit) {
    this.seekers = seekers;
    this.episodes = episodes;
    this.audit = audit;
  }

  /** One cycle; length is null for the newest, which is incomplete. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Cycle(
      LocalDate start,
      LocalDate end,
      LocalDate periodEnd,
      Integer lengthDays,
      Integer periodDays,
      int veryHeavyDays,
      boolean complete) {

    static Cycle of(Episode e) {
      Integer veryHeavy = e.metric("very_heavy_days");
      return new Cycle(
          e.start(),
          e.end(),
          e.activeEnd(),
          e.metric("length_days"),
          e.metric("period_days"),
          veryHeavy == null ? 0 : veryHeavy,
          e.complete());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CycleSummary(List<Cycle> cycles, Integer cycleDay) {}

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
    return new CycleSummary(
        episodes.episodes(user, MODEL).stream().map(Cycle::of).toList(),
        episodes.currentDay(user, MODEL, zone).orElse(null));
  }
}
