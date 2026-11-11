package care.solace.tracking.cycles;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.Seekers;
import care.solace.tracking.episodes.Episode;
import care.solace.tracking.episodes.EpisodeModels;
import care.solace.tracking.episodes.EpisodeReconciler;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cycle calendar and summary (tag cycles; D50): facts only, never a forecast. Read from the
 * {@code menstrual_cycle} episode model (D61). {@code /v1/tracking/episodes/{model}} gives any
 * model's episodes and current day, for the model a module's presentation names.
 */
@RestController
class CycleController {

  static final String MODEL = "menstrual_cycle";

  private final Seekers seekers;
  private final EpisodeReconciler episodes;
  private final EpisodeModels models;
  private final AuditLog audit;

  CycleController(
      Seekers seekers, EpisodeReconciler episodes, EpisodeModels models, AuditLog audit) {
    this.seekers = seekers;
    this.episodes = episodes;
    this.models = models;
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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record EpisodeView(
      LocalDate start,
      LocalDate end,
      LocalDate activeEnd,
      boolean complete,
      Map<String, Integer> metrics) {

    static EpisodeView of(Episode e) {
      return new EpisodeView(e.start(), e.end(), e.activeEnd(), e.complete(), e.metrics());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record EpisodeSummary(String model, List<EpisodeView> episodes, Integer currentDay) {}

  /** One model's episodes, oldest first, and today's day of the newest; facts only. */
  @GetMapping("/v1/tracking/episodes/{model}")
  EpisodeSummary episodes(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String model,
      @RequestParam(defaultValue = "Africa/Douala") String timeZone) {
    UUID user = seekers.require(token);
    models.find(model).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    audit.read("tracking.episodes.read", "episodes", null, user.toString());
    return new EpisodeSummary(
        model,
        episodes.episodes(user, model).stream().map(EpisodeView::of).toList(),
        episodes.currentDay(user, model, zone(timeZone)).orElse(null));
  }

  private static ZoneId zone(String timeZone) {
    try {
      return ZoneId.of(timeZone);
    } catch (DateTimeException e) {
      return ZoneId.of("Africa/Douala");
    }
  }

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
