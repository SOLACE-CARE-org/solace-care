package care.solace.tracking.episodes;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.episodes.EpisodeModel.Metric;
import care.solace.tracking.episodes.EpisodeModel.MetricKind;
import care.solace.tracking.episodes.EpisodeModel.Span;
import care.solace.tracking.modules.StaffGate;
import care.solace.tracking.rules.HistoryLibrary;
import care.solace.tracking.rules.SyntheticHistories;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The episode model builder (admin screen 33, provisional; tag admin-episode-models; D61). Every
 * change is audited: who drafted, who approved, who published or retired.
 */
@RestController
class AdminEpisodeModelsController {

  private static final String CODE = "^[a-z][a-z0-9_]*$";

  private final StaffGate gate;
  private final EpisodeModelDrafts drafts;
  private final HistoryLibrary histories;
  private final AuditLog audit;
  private final Clock clock;

  AdminEpisodeModelsController(
      StaffGate gate,
      EpisodeModelDrafts drafts,
      HistoryLibrary histories,
      AuditLog audit,
      Clock clock) {
    this.gate = gate;
    this.drafts = drafts;
    this.histories = histories;
    this.audit = audit;
    this.clock = clock;
  }

  record MetricRequest(
      @NotBlank @Pattern(regexp = CODE) @Size(max = 40) String code,
      @NotNull MetricKind kind,
      @Size(max = 40) String value,
      @NotNull JsonNode labels) {}

  record DraftRequest(
      @NotBlank @Pattern(regexp = CODE) @Size(max = 40) String code,
      @NotBlank @Size(max = 40) String startElement,
      @Size(max = 40) String endElement,
      @Size(max = 40) String activityElement,
      @NotNull @Size(max = 12) List<@NotBlank @Size(max = 40) String> activeValues,
      @Min(1) @Max(365) int gapDays,
      @NotNull Span span,
      @NotNull @Size(max = EpisodeModelRules.MAX_METRICS) List<@Valid MetricRequest> metrics,
      @NotNull JsonNode labels) {}

  record ReviewRequest(@NotNull Boolean approve, @Size(max = 4000) String comments) {}

  record MetricView(String code, MetricKind kind, String value, JsonNode labels) {}

  record ReviewView(String reviewer, String decision, String comments, Instant decidedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ModelVersionView(
      UUID id,
      String code,
      int version,
      String status,
      String startElement,
      String endElement,
      String activityElement,
      List<String> activeValues,
      int gapDays,
      Span span,
      List<MetricView> metrics,
      List<String> signals,
      JsonNode labels,
      List<String> problems,
      Integer usedBy,
      List<ReviewView> reviews,
      String draftedBy,
      String approvedBy,
      boolean canApprove,
      Instant publishedAt) {}

  record EpisodeView(
      LocalDate start,
      LocalDate end,
      LocalDate activeEnd,
      boolean complete,
      Map<String, Integer> metrics) {}

  record TestRunView(String history, JsonNode historyLabels, List<EpisodeView> episodes) {}

  @GetMapping("/admin/v1/tracking/episode-model-versions")
  List<ModelVersionView> list(@AuthenticationPrincipal Jwt token) {
    var staff = gate.require(token, false);
    return drafts.all().stream().map(v -> view(v, staff, false)).toList();
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions")
  @ResponseStatus(HttpStatus.CREATED)
  ModelVersionView create(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.create(model(request), staff);
    audit.adminAction(
        "tracking.episode-model.create",
        "episode-model-version",
        v.id().toString(),
        Map.of("code", request.code(), "version", String.valueOf(v.model().version())));
    return view(v, staff, true);
  }

  @GetMapping("/admin/v1/tracking/episode-model-versions/{id}")
  ModelVersionView version(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, false);
    return view(found(id), staff, true);
  }

  @PutMapping("/admin/v1/tracking/episode-model-versions/{id}")
  ModelVersionView save(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.save(id, model(request), staff);
    audit.adminAction(
        "tracking.episode-model.save",
        "episode-model-version",
        id.toString(),
        Map.of("version", String.valueOf(v.model().version())));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions/{id}/test-run")
  List<TestRunView> testRun(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    gate.require(token, false);
    var model = found(id).model();
    LocalDate today = LocalDate.now(clock);
    List<TestRunView> out = new ArrayList<>();
    for (var stored : histories.all()) {
      var history =
          SyntheticHistories.build(stored.code(), stored.recipe(), stored.context(), today);
      var episodes = history.signals(today, List.of(model)).episodes(model.code());
      out.add(
          new TestRunView(
              stored.code(),
              stored.labels(),
              episodes.stream()
                  .map(
                      e ->
                          new EpisodeView(
                              e.start(), e.end(), e.activeEnd(), e.complete(), e.metrics()))
                  .toList()));
    }
    return out;
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions/{id}/submission")
  ModelVersionView submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.submit(id, staff);
    audit.adminAction(
        "tracking.episode-model.submit", "episode-model-version", id.toString(), Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions/{id}/review")
  ModelVersionView review(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.review(id, request.approve(), request.comments(), staff);
    audit.adminAction(
        request.approve() ? "tracking.episode-model.approve" : "tracking.episode-model.return",
        "episode-model-version",
        id.toString(),
        Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions/{id}/publication")
  ModelVersionView publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.publish(id, staff);
    audit.adminAction(
        "tracking.episode-model.publish",
        "episode-model-version",
        id.toString(),
        Map.of("code", v.model().code(), "version", String.valueOf(v.model().version())));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/episode-model-versions/{id}/retirement")
  ModelVersionView retire(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.retire(id, staff);
    audit.adminAction(
        "tracking.episode-model.retire",
        "episode-model-version",
        id.toString(),
        Map.of("code", v.model().code(), "version", String.valueOf(v.model().version())));
    return view(v, staff, true);
  }

  private EpisodeModelDrafts.Version found(UUID id) {
    return drafts.version(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /** The request as a model; shapes the form cannot fix are refused, the rest become problems. */
  static EpisodeModel model(DraftRequest r) {
    String activity = blank(r.activityElement());
    boolean fine =
        r.labels().isObject()
            && (activity != null || r.activeValues().isEmpty())
            && r.metrics().stream().allMatch(m -> m.labels().isObject())
            && r.labels().toString().length() <= 1000;
    if (!fine) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    List<Metric> metrics = new ArrayList<>();
    for (var m : r.metrics()) {
      metrics.add(
          new Metric(
              m.code(),
              m.kind(),
              m.kind() == MetricKind.DAYS_WITH_VALUE ? blank(m.value()) : null,
              m.labels()));
    }
    return new EpisodeModel(
        r.code(),
        0,
        r.startElement().trim(),
        blank(r.endElement()),
        activity,
        new LinkedHashSet<>(r.activeValues()),
        r.gapDays(),
        r.span(),
        List.copyOf(metrics),
        r.labels());
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  /**
   * @param full with problems, reviews and use; the list leaves them out
   */
  private ModelVersionView view(EpisodeModelDrafts.Version v, StaffGate.Staff staff, boolean full) {
    var m = v.model();
    boolean canApprove =
        staff.clinicalReviewer()
            && v.status().equals("IN_REVIEW")
            && v.approvedBy() == null
            && !staff.id().equals(v.draftedBy());
    return new ModelVersionView(
        v.id(),
        m.code(),
        m.version(),
        v.status(),
        m.startElement(),
        m.endElement(),
        m.activityElement(),
        List.copyOf(m.activeValues()),
        m.gapDays(),
        m.span(),
        m.metrics().stream()
            .map(x -> new MetricView(x.code(), x.kind(), x.value(), x.labels()))
            .toList(),
        m.metrics().stream().map(m::signal).toList(),
        m.labels(),
        v.open() ? drafts.problems(v) : List.of(),
        full ? drafts.usedBy(m.code()) : null,
        full
            ? drafts.reviews(v.id()).stream()
                .map(
                    r ->
                        new ReviewView(r.reviewerName(), r.decision(), r.comments(), r.decidedAt()))
                .toList()
            : null,
        v.draftedByName(),
        v.approvedByName(),
        canApprove,
        v.publishedAt());
  }
}
