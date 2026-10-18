package care.solace.core.healthcheck;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.catalog.Catalogue;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Definition.Rule;
import care.solace.core.healthcheck.Definition.TestSet;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin screens 28 to 31 (tag admin-health-check; D55). The health check is written by a Clinical
 * reviewer and approved by a different one (Admin Portal Spec, Roles); test runs use made-up answer
 * sets only, and no screen shows anyone's answers.
 */
@RestController
class AdminHealthCheckController {

  static final String AREA = "healthCheck";

  private final StaffAccess access;
  private final Versions versions;
  private final Responses responses;
  private final Catalogue catalogue;
  private final AuditLog audit;
  private final care.solace.core.admin.PlatformSettings settings;

  AdminHealthCheckController(
      StaffAccess access,
      Versions versions,
      Responses responses,
      Catalogue catalogue,
      AuditLog audit,
      care.solace.core.admin.PlatformSettings settings) {
    this.access = access;
    this.versions = versions;
    this.responses = responses;
    this.catalogue = catalogue;
    this.audit = audit;
    this.settings = settings;
  }

  record Count(String code, int count) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Row(
      UUID id,
      String number,
      String status,
      String draftedBy,
      String approvedBy,
      Instant publishedAt,
      List<Count> topSpecialties,
      int completions,
      int safetyStops) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record View(
      UUID id,
      String number,
      String status,
      List<Question> questions,
      List<Rule> rules,
      List<String> specialtyOrder,
      List<TestSet> testSets,
      List<Versions.Review> reviews,
      List<String> problems,
      String draftedBy,
      String approvedBy,
      boolean canApprove,
      Instant publishedAt) {}

  record Scoring(
      @NotNull @Size(max = 100) List<Rule> rules, @NotNull List<String> specialtyOrder) {}

  record ReviewRequest(@NotNull Boolean approve, @Size(max = 4000) String comments) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TestResult(
      String name,
      boolean safetyStop,
      String specialty,
      String alsoFit,
      String expected,
      String published,
      Boolean publishedSafetyStop) {}

  @GetMapping("/admin/v1/health-check/versions")
  List<Row> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, StaffAccess.Level.VIEW);
    return versions.all().stream()
        .map(
            v -> {
              var c = responses.counts(v.id());
              return new Row(
                  v.id(),
                  v.number(),
                  v.status(),
                  v.draftedByName(),
                  v.approvedByName(),
                  v.publishedAt(),
                  c.specialties().entrySet().stream()
                      .limit(3)
                      .map(e -> new Count(e.getKey(), e.getValue()))
                      .toList(),
                  c.completions(),
                  c.safetyStops());
            })
        .toList();
  }

  record Stats(
      int completedLast7Days,
      int startedLast7Days,
      int safetyStopsLast7Days,
      List<Count> suggestionsLast30Days) {}

  @GetMapping("/admin/v1/health-check/stats")
  Stats stats(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, StaffAccess.Level.VIEW);
    var s = responses.stats();
    return new Stats(
        s.completed(),
        s.started(),
        s.safetyStops(),
        s.suggestions().entrySet().stream().map(e -> new Count(e.getKey(), e.getValue())).toList());
  }

  @PostMapping("/admin/v1/health-check/versions")
  @ResponseStatus(HttpStatus.CREATED)
  View newDraft(@AuthenticationPrincipal Jwt token) {
    var staff = writer(token);
    var v = versions.newDraft(staff);
    audit.adminAction(
        "health-check.version.create", "health-check-version", v.id().toString(), Map.of());
    return view(v, staff);
  }

  @GetMapping("/admin/v1/health-check/versions/{versionId}")
  View get(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = access.require(token, AREA, StaffAccess.Level.VIEW);
    return view(versions.require(versionId), staff);
  }

  @PutMapping("/admin/v1/health-check/versions/{versionId}/questions")
  View questions(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody @Size(min = 1, max = 60) List<Question> body) {
    var staff = writer(token);
    var v = versions.saveQuestions(versionId, body);
    audit.adminAction(
        "health-check.questions.save",
        "health-check-version",
        versionId.toString(),
        Map.of("questions", String.valueOf(body.size())));
    return view(v, staff);
  }

  @PutMapping("/admin/v1/health-check/versions/{versionId}/scoring")
  View scoring(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody Scoring body) {
    var staff = writer(token);
    var v = versions.saveScoring(versionId, body.rules(), body.specialtyOrder());
    audit.adminAction(
        "health-check.scoring.save",
        "health-check-version",
        versionId.toString(),
        Map.of("rules", String.valueOf(body.rules().size())));
    return view(v, staff);
  }

  @PutMapping("/admin/v1/health-check/versions/{versionId}/test-sets")
  View testSets(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody @Size(max = 50) List<TestSet> body) {
    var staff = writer(token);
    var v = versions.saveTestSets(versionId, body);
    audit.adminAction(
        "health-check.test-sets.save", "health-check-version", versionId.toString(), Map.of());
    return view(v, staff);
  }

  @PostMapping("/admin/v1/health-check/versions/{versionId}/test-run")
  List<TestResult> testRun(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    access.require(token, AREA, StaffAccess.Level.VIEW);
    var v = versions.require(versionId);
    Optional<Versions.Version> live = versions.published().filter(p -> !p.id().equals(v.id()));
    int within = (int) settings.number("solace.health-check.also-fit-within", 1);
    List<TestResult> out = new ArrayList<>();
    for (TestSet set : v.testSets()) {
      var answers = set.answers() == null ? Map.<String, List<String>>of() : set.answers();
      boolean stop = Engine.safetyStop(v.questions(), answers).isPresent();
      var mine =
          stop
              ? null
              : Engine.suggest(v.questions(), v.rules(), v.specialtyOrder(), answers, within);
      Boolean liveStop =
          live.map(p -> Engine.safetyStop(p.questions(), answers).isPresent()).orElse(null);
      String liveSpecialty =
          live.filter(p -> !Boolean.TRUE.equals(liveStop))
              .map(
                  p ->
                      Engine.suggest(p.questions(), p.rules(), p.specialtyOrder(), answers, within)
                          .specialty())
              .orElse(null);
      out.add(
          new TestResult(
              set.name(),
              stop,
              mine == null ? null : mine.specialty(),
              mine == null ? null : mine.alsoFit(),
              set.expected(),
              liveSpecialty,
              liveStop));
    }
    return out;
  }

  @PostMapping("/admin/v1/health-check/versions/{versionId}/submission")
  View submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = writer(token);
    var v = versions.submit(versionId, specialties());
    audit.adminAction(
        "health-check.version.submit", "health-check-version", versionId.toString(), Map.of());
    return view(v, staff);
  }

  @PostMapping("/admin/v1/health-check/versions/{versionId}/review")
  View review(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody ReviewRequest body) {
    var staff = access.require(token, AREA, StaffAccess.Level.CHANGE);
    if (!staff.roles().contains("CLINICAL_REVIEWER")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    var v = versions.review(versionId, staff, body.approve(), body.comments());
    audit.adminAction(
        body.approve() ? "health-check.version.approve" : "health-check.version.return",
        "health-check-version",
        versionId.toString(),
        body.comments(),
        Map.of());
    return view(v, staff);
  }

  @PostMapping("/admin/v1/health-check/versions/{versionId}/publication")
  View publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = writer(token);
    var v = versions.publish(versionId);
    audit.adminAction(
        "health-check.version.publish", "health-check-version", versionId.toString(), Map.of());
    return view(v, staff);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** Writing, submitting and publishing: a Clinical reviewer or a Super admin. */
  private StaffAccess.Staff writer(Jwt token) {
    var staff = access.require(token, AREA, StaffAccess.Level.CHANGE);
    if (!staff.roles().contains("CLINICAL_REVIEWER") && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return staff;
  }

  private Set<String> specialties() {
    return catalogue.specialties(Locale.ENGLISH).stream()
        .map(Catalogue.Entry::code)
        .collect(Collectors.toSet());
  }

  private View view(Versions.Version v, StaffAccess.Staff staff) {
    boolean canApprove =
        v.status().equals("IN_REVIEW")
            && v.approvedBy() == null
            && staff.roles().contains("CLINICAL_REVIEWER")
            && !v.draftedBy().equals(staff.id());
    return new View(
        v.id(),
        v.number(),
        v.status(),
        v.questions(),
        v.rules(),
        v.specialtyOrder(),
        v.testSets(),
        versions.reviews(v.id()),
        v.status().equals("DRAFT") ? Versions.problems(v, specialties()) : List.of(),
        v.draftedByName(),
        v.approvedByName(),
        canApprove,
        v.publishedAt());
  }
}
