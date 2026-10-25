package care.solace.jobs;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * This service's scheduled jobs for staff (D61): their rows, whether a saved change still waits for
 * a restart, and how the last run went. A change is a new version with a reason, audited, and takes
 * effect when the service next starts. Locked jobs cannot be changed.
 */
@RestController
class JobsController {

  private final JobAccess access;
  private final JobTriggerStore store;
  private final AuditLog audit;
  private final Clock clock;

  JobsController(JobAccess access, JobTriggerStore store, AuditLog audit, Clock clock) {
    this.access = access;
    this.store = store;
    this.audit = audit;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record View(
      String name,
      String description,
      JobTriggerStore.Status status,
      boolean caution,
      JobTriggerStore.Kind kind,
      String schedule,
      String zone,
      int version,
      String updatedBy,
      Instant updatedAt,
      boolean pendingRestart,
      Instant lastStartedAt,
      Instant lastFinishedAt,
      String lastOutcome,
      Instant nextRunAt) {

    static View of(JobTriggerStore.Trigger t) {
      return new View(
          t.name(),
          t.description(),
          t.status(),
          t.caution(),
          t.kind(),
          t.schedule(),
          t.zone(),
          t.version(),
          t.updatedByName(),
          t.updatedAt(),
          t.pendingRestart(),
          t.lastStartedAt(),
          t.lastFinishedAt(),
          t.lastOutcome(),
          t.nextRunAt());
    }
  }

  record Change(
      @Min(1) int version,
      @NotNull JobTriggerStore.Status status,
      @NotBlank @Size(max = 120) String schedule,
      @Size(max = 60) String zone,
      @NotBlank @Size(max = 500) String reason) {}

  @GetMapping("/admin/v1/jobs")
  List<View> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, false);
    return store.all().stream().map(View::of).toList();
  }

  @PutMapping("/admin/v1/jobs/{name}")
  View change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String name,
      @Valid @RequestBody Change body) {
    var staff = access.require(token, true);
    var row = store.find(name).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    String zone = body.zone() == null || body.zone().isBlank() ? null : body.zone().strip();
    String schedule = body.schedule().strip();
    if (!Schedules.valid(row.kind(), schedule, zone)) {
      throw new SolaceException(JobError.SCHEDULE_INVALID);
    }
    if (row.kind() == JobTriggerStore.Kind.INTERVAL) {
      schedule = Schedules.interval(schedule).toString();
    }
    if (!store.change(
        name,
        body.version(),
        body.status(),
        row.kind(),
        schedule,
        zone,
        body.reason().strip(),
        staff.id(),
        staff.name(),
        clock.instant())) {
      throw new SolaceException(JobError.CHANGED);
    }
    audit.adminAction(
        "jobs.change",
        "job",
        name,
        body.reason().strip(),
        Map.of("status", body.status().name(), "version", String.valueOf(body.version() + 1)));
    return View.of(store.find(name).orElseThrow());
  }

  @GetMapping("/admin/v1/jobs/{name}/history")
  List<JobTriggerStore.Version> history(
      @AuthenticationPrincipal Jwt token, @PathVariable String name) {
    access.require(token, false);
    store.find(name).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return store.versions(name);
  }
}
