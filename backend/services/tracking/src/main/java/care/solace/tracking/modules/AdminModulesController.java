package care.solace.tracking.modules;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.context.ContextQuestion;
import care.solace.tracking.context.ContextQuestions;
import care.solace.tracking.context.QuestionView;
import care.solace.tracking.elements.ElementController;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.episodes.EpisodeModels;
import care.solace.tracking.rules.HistoryLibrary;
import care.solace.tracking.rules.RuleRules;
import care.solace.tracking.rules.SyntheticHistories;
import care.solace.tracking.rules.TestRuns;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The module builder (admin screens 21 to 25; tag admin-modules; D51). Every step is audited: who
 * changed what, who approved, who published.
 */
@RestController
class AdminModulesController {

  private final StaffGate gate;
  private final Modules modules;
  private final ModuleStore store;
  private final ElementLibrary library;
  private final ImageAssets images;
  private final AuditLog audit;
  private final EpisodeModels models;
  private final ContextQuestions questions;
  private final HistoryLibrary histories;
  private final TestRuns testRuns;
  private final Clock clock;

  AdminModulesController(
      StaffGate gate,
      Modules modules,
      ModuleStore store,
      ElementLibrary library,
      ImageAssets images,
      AuditLog audit,
      EpisodeModels models,
      ContextQuestions questions,
      HistoryLibrary histories,
      TestRuns testRuns,
      Clock clock) {
    this.gate = gate;
    this.modules = modules;
    this.store = store;
    this.library = library;
    this.images = images;
    this.audit = audit;
    this.models = models;
    this.questions = questions;
    this.histories = histories;
    this.testRuns = testRuns;
    this.clock = clock;
  }

  // --- Views ------------------------------------------------------------------------------------

  public record Text(String en, String fr) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record VersionSummary(
      UUID id,
      String number,
      String status,
      String draftedBy,
      String approvedBy,
      Instant publishedAt,
      int elements,
      int rules) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ModuleRow(
      UUID id,
      String code,
      String accentColour,
      Text name,
      List<VersionSummary> versions,
      Integer enrolled) {}

  /** Elements shown together under one EN/FR label on the logging screen (D61). */
  public record Group(
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") @Size(max = 40) String code,
      @NotNull Text labels) {

    static Group of(ModuleStore.Binding b) {
      if (b.groupCode() == null) {
        return null;
      }
      var l = b.groupLabel();
      return new Group(
          b.groupCode(),
          new Text(
              l == null ? "" : l.path("en").asText(""), l == null ? "" : l.path("fr").asText("")));
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record BindingView(String code, String version, boolean required, int position, Group group) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RuleView(
      UUID id,
      String code,
      JsonNode conditions,
      JsonNode unless,
      String outcome,
      List<String> specialtyCodes,
      String messageEn,
      String messageFr,
      int cooldownDays,
      Integer repeatWithinDays,
      int position,
      List<String> problems) {}

  record ReviewView(String reviewer, String decision, String comments, Instant decidedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record VersionView(
      UUID id,
      UUID moduleId,
      String moduleCode,
      String accentColour,
      String number,
      String status,
      Text name,
      Text description,
      Text audience,
      Text imageAlt,
      UUID imageId,
      Integer minAge,
      JsonNode enrolmentQuestion,
      List<String> questions,
      JsonNode presentation,
      LocalTime reminderDefault,
      List<BindingView> elements,
      List<RuleView> rules,
      List<ReviewView> reviews,
      List<String> problems,
      List<String> warnings,
      String draftedBy,
      String approvedBy,
      boolean canApprove,
      Instant publishedAt) {}

  record NewModule(
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") @Size(max = 40) String code,
      @NotBlank String accentColour) {}

  record DetailsRequest(
      @NotNull @Size(max = 80) String nameEn,
      @NotNull @Size(max = 80) String nameFr,
      @NotNull @Size(max = 200) String descriptionEn,
      @NotNull @Size(max = 200) String descriptionFr,
      UUID imageId,
      @NotNull @Size(max = 200) String imageAltEn,
      @NotNull @Size(max = 200) String imageAltFr,
      @Min(18) @Max(120) Integer minAge,
      @NotNull @Size(max = 120) String audienceEn,
      @NotNull @Size(max = 120) String audienceFr,
      JsonNode enrolmentQuestion,
      LocalTime reminderDefault,
      String accentColour) {}

  record BindingRequest(
      @NotBlank String code, @NotBlank String version, boolean required, @Valid Group group) {}

  record RuleRequest(
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$") String code,
      @NotNull JsonNode conditions,
      JsonNode unless,
      @NotBlank @Pattern(regexp = "PATTERN|INVITE") String outcome,
      List<@Pattern(regexp = "^[a-z][a-z0-9-]*$") String> specialtyCodes,
      @NotBlank @Size(max = 300) String messageEn,
      @NotBlank @Size(max = 300) String messageFr,
      @Min(0) @Max(365) int cooldownDays,
      @Min(1) @Max(365) Integer repeatWithinDays,
      @Min(0) Integer position) {}

  record ReviewRequest(@NotNull Boolean approve, @Size(max = 4000) String comments) {}

  record TestResult(
      String history,
      JsonNode historyLabels,
      String ruleCode,
      boolean fires,
      boolean blockedByUnless,
      JsonNode evidence) {}

  /** A made-up history: its recipe and the answers "unless" reads (D61). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record HistoryView(
      String code,
      JsonNode labels,
      JsonNode recipe,
      JsonNode context,
      boolean seeded,
      String createdByName,
      Instant createdAt) {
    static HistoryView of(HistoryLibrary.Stored h) {
      return new HistoryView(
          h.code(),
          h.labels(),
          h.recipe(),
          h.context(),
          h.seeded(),
          h.createdByName(),
          h.createdAt());
    }
  }

  record HistoryRequest(
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{1,59}$") String code,
      @NotNull @Valid Text labels,
      @NotNull JsonNode recipe,
      JsonNode context) {}

  /** One way of reading a signal, and the comparisons it allows there. */
  record SignalOperator(String code, List<String> comparisons) {}

  /** A signal this version's rules may read (D61). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record VocabularySignal(
      String key,
      String signal,
      String element,
      String kind,
      String valueType,
      List<String> values,
      JsonNode labels,
      List<SignalOperator> operators) {}

  /** An operator, the condition fields the builder shows for it and its message placeholders. */
  record VocabularyOperator(String code, List<String> fields, List<String> evidenceKeys) {}

  /** Everything the rule form may offer for one version, from the lists the validator uses. */
  record RuleVocabulary(
      List<VocabularySignal> signals,
      List<VocabularyOperator> operators,
      List<String> unlessFields,
      List<QuestionView> questions) {}

  // --- Library and modules ----------------------------------------------------------------------

  @GetMapping("/admin/v1/tracking/elements")
  List<ElementController.ElementView> elements(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return library.all().stream().map(ElementController::view).toList();
  }

  @GetMapping("/admin/v1/tracking/modules")
  List<ModuleRow> list(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return store.modules().stream()
        .map(
            m -> {
              var versions = store.versions(m.id());
              var latest = versions.isEmpty() ? null : versions.get(0);
              return new ModuleRow(
                  m.id(),
                  m.code(),
                  m.accentColour(),
                  latest == null
                      ? new Text("", "")
                      : new Text(latest.details().nameEn(), latest.details().nameFr()),
                  versions.stream()
                      .map(
                          v ->
                              new VersionSummary(
                                  v.id(),
                                  v.number(),
                                  v.status(),
                                  v.draftedByName(),
                                  v.approvedByName(),
                                  v.publishedAt(),
                                  store.bindings(v.id()).size(),
                                  store.rules(v.id()).size()))
                      .toList(),
                  store.enrolled(m.id()));
            })
        .toList();
  }

  @PostMapping("/admin/v1/tracking/modules")
  @ResponseStatus(HttpStatus.CREATED)
  VersionView create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody NewModule request) {
    var staff = gate.require(token, true);
    var v = modules.create(request.code(), request.accentColour(), staff);
    audit.adminAction(
        "tracking.module.create",
        "module",
        v.moduleId().toString(),
        Map.of("code", request.code()));
    return view(v, staff);
  }

  @PostMapping("/admin/v1/tracking/modules/{moduleId}/versions")
  @ResponseStatus(HttpStatus.CREATED)
  VersionView newVersion(@AuthenticationPrincipal Jwt token, @PathVariable UUID moduleId) {
    var staff = gate.require(token, true);
    var v = modules.newVersion(moduleId, staff);
    audit.adminAction("tracking.version.create", "module-version", v.id().toString(), Map.of());
    return view(v, staff);
  }

  @GetMapping("/admin/v1/tracking/versions/{versionId}")
  VersionView version(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, false);
    return view(found(versionId), staff);
  }

  @PutMapping("/admin/v1/tracking/versions/{versionId}/details")
  VersionView details(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody DetailsRequest r) {
    var staff = gate.require(token, true);
    if (r.imageId() != null) {
      images.asset(r.imageId()).orElseThrow(() -> new SolaceException(CommonError.REQUEST_INVALID));
    }
    modules.saveDetails(
        versionId,
        new ModuleStore.Details(
            r.nameEn().trim(),
            r.nameFr().trim(),
            r.descriptionEn().trim(),
            r.descriptionFr().trim(),
            r.imageId(),
            r.imageAltEn().trim(),
            r.imageAltFr().trim(),
            r.minAge(),
            r.audienceEn().trim(),
            r.audienceFr().trim(),
            r.enrolmentQuestion() == null || r.enrolmentQuestion().isNull()
                ? null
                : r.enrolmentQuestion(),
            r.reminderDefault(),
            null),
        r.accentColour(),
        staff);
    audit.adminAction("tracking.version.details", "module-version", versionId.toString(), Map.of());
    return view(found(versionId), staff);
  }

  @PutMapping("/admin/v1/tracking/versions/{versionId}/elements")
  VersionView elements(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody @Size(min = 1, max = 20) List<@Valid BindingRequest> request) {
    var staff = gate.require(token, true);
    List<ModuleStore.Binding> bindings = new ArrayList<>();
    for (int i = 0; i < request.size(); i++) {
      var b = request.get(i);
      var e =
          library
              .find(b.code(), b.version())
              .orElseThrow(() -> new SolaceException(CommonError.REQUEST_INVALID));
      var g = b.group();
      bindings.add(
          new ModuleStore.Binding(
              e.id(),
              b.required(),
              i + 1,
              g == null ? null : g.code(),
              g == null ? null : ModuleStore.JSON.valueToTree(g.labels())));
    }
    modules.saveBindings(versionId, bindings, staff);
    audit.adminAction(
        "tracking.version.elements",
        "module-version",
        versionId.toString(),
        Map.of("count", String.valueOf(bindings.size())));
    return view(found(versionId), staff);
  }

  @PostMapping("/admin/v1/tracking/versions/{versionId}/rules")
  @ResponseStatus(HttpStatus.CREATED)
  VersionView addRule(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody RuleRequest r) {
    var staff = gate.require(token, true);
    var saved = modules.saveRule(rule(UUID.randomUUID(), versionId, r), staff);
    audit.adminAction(
        "tracking.rule.save", "rule", saved.id().toString(), Map.of("code", saved.code()));
    return view(found(versionId), staff);
  }

  @PutMapping("/admin/v1/tracking/rules/{ruleId}")
  VersionView saveRule(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID ruleId,
      @Valid @RequestBody RuleRequest r) {
    var staff = gate.require(token, true);
    var existing = store.rule(ruleId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    modules.saveRule(rule(ruleId, existing.moduleVersionId(), r), staff);
    audit.adminAction("tracking.rule.save", "rule", ruleId.toString(), Map.of("code", r.code()));
    return view(found(existing.moduleVersionId()), staff);
  }

  @DeleteMapping("/admin/v1/tracking/rules/{ruleId}")
  VersionView deleteRule(@AuthenticationPrincipal Jwt token, @PathVariable UUID ruleId) {
    var staff = gate.require(token, true);
    var existing = store.rule(ruleId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    modules.deleteRule(ruleId, staff);
    audit.adminAction("tracking.rule.delete", "rule", ruleId.toString(), Map.of());
    return view(found(existing.moduleVersionId()), staff);
  }

  /** The published context questions a module may ask and a rule's "unless" may read (D61). */
  @GetMapping("/admin/v1/tracking/context-questions")
  List<QuestionView> contextQuestions(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return questions.all().stream().map(QuestionView::of).toList();
  }

  /** How the app shows the module (D61); an empty body clears it. */
  @PutMapping("/admin/v1/tracking/versions/{versionId}/presentation")
  VersionView presentation(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @RequestBody JsonNode presentation) {
    var staff = gate.require(token, true);
    modules.savePresentation(versionId, presentation, staff);
    audit.adminAction(
        "tracking.version.presentation", "module-version", versionId.toString(), Map.of());
    return view(found(versionId), staff);
  }

  /** The context questions asked on enrolment, in order; at most three (D61). */
  @PutMapping("/admin/v1/tracking/versions/{versionId}/questions")
  VersionView questions(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @RequestBody @Size(max = Modules.MAX_QUESTIONS) List<@NotBlank String> codes) {
    var staff = gate.require(token, true);
    modules.saveQuestions(versionId, codes, staff);
    audit.adminAction(
        "tracking.version.questions",
        "module-version",
        versionId.toString(),
        Map.of("count", String.valueOf(codes.size())));
    return view(found(versionId), staff);
  }

  @PostMapping(value = "/admin/v1/tracking/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  ImageAssets.Asset upload(
      @AuthenticationPrincipal Jwt token, @RequestPart("file") MultipartFile file)
      throws IOException {
    var staff = gate.require(token, true);
    Modules.requireDrafter(staff);
    var asset = images.upload(file.getBytes(), staff.id());
    audit.adminAction("tracking.image.upload", "image", asset.id().toString(), Map.of());
    return asset;
  }

  /**
   * What the rule form may offer for this version: its signals, the operators that can read each
   * and their comparisons, and the "unless" fields (D61). The validator checks against the same
   * lists, so the builder keeps no copy of its own.
   */
  @GetMapping("/admin/v1/tracking/versions/{versionId}/rule-vocabulary")
  RuleVocabulary ruleVocabulary(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    gate.require(token, false);
    var v = found(versionId);
    var operators = care.solace.tracking.rules.Operators.all();
    var signals =
        care.solace.tracking.rules.SignalCatalog.forModule(modules.bound(v.id()), models.all())
            .stream()
            .map(
                s ->
                    new VocabularySignal(
                        s.key(),
                        s.signal(),
                        s.element(),
                        s.kind().name(),
                        s.valueType().name(),
                        s.values(),
                        s.labels(),
                        operators.stream()
                            .filter(o -> o.accepts(s))
                            .map(o -> new SignalOperator(o.code(), o.comparisons(s)))
                            .toList()))
            .toList();
    return new RuleVocabulary(
        signals,
        operators.stream()
            .map(o -> new VocabularyOperator(o.code(), o.fields(), o.evidenceKeys()))
            .toList(),
        questions.all().stream().map(ContextQuestion::code).toList(),
        questions.all().stream().map(QuestionView::of).toList());
  }

  /** Runs each rule of the draft against the made-up histories; never real people's data. */
  @PostMapping("/admin/v1/tracking/versions/{versionId}/test-run")
  List<TestResult> testRun(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    gate.require(token, false);
    var v = found(versionId);
    return testRuns.run(Modules.testable(store.rules(v.id()))).stream()
        .map(
            r ->
                new TestResult(
                    r.history(),
                    r.historyLabels(),
                    r.rule(),
                    r.fires(),
                    r.blockedByUnless(),
                    ModuleStore.JSON.valueToTree(r.conditions())))
        .toList();
  }

  /** The made-up histories rules are tested against, seeded ones first (D61). */
  @GetMapping("/admin/v1/tracking/synthetic-histories")
  List<HistoryView> histories(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return histories.all().stream().map(HistoryView::of).toList();
  }

  /**
   * Adds a history from a recipe. Drafters and Clinical reviewers add them next to the rules they
   * write or review; a history is never edited, so a past test run stays explainable.
   */
  @PostMapping("/admin/v1/tracking/synthetic-histories")
  @ResponseStatus(HttpStatus.CREATED)
  HistoryView addHistory(@AuthenticationPrincipal Jwt token, @Valid @RequestBody HistoryRequest r) {
    var staff = gate.require(token, true);
    if (!staff.clinicalReviewer()
        && !staff.roles().contains("OPERATIONS")
        && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    String en = r.labels().en() == null ? "" : r.labels().en().trim();
    String fr = r.labels().fr() == null ? "" : r.labels().fr().trim();
    JsonNode context =
        r.context() == null || r.context().isNull()
            ? ModuleStore.JSON.createObjectNode()
            : r.context();
    if (en.isEmpty()
        || fr.isEmpty()
        || en.length() > 80
        || fr.length() > 80
        || !SyntheticHistories.problems(
                r.recipe(),
                context,
                code -> library.latest(code).orElse(null),
                code -> questions.find(code).orElse(null))
            .isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (histories.exists(r.code())) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    histories.add(
        r.code(),
        ModuleStore.JSON.valueToTree(new Text(en, fr)),
        r.recipe(),
        context,
        staff.id(),
        staff.name(),
        clock.instant());
    audit.adminAction("tracking.history.add", "synthetic-history", r.code(), Map.of());
    return histories.all().stream()
        .filter(h -> h.code().equals(r.code()))
        .findFirst()
        .map(HistoryView::of)
        .orElseThrow();
  }

  @PostMapping("/admin/v1/tracking/versions/{versionId}/submission")
  VersionView submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, true);
    modules.submit(versionId, staff);
    audit.adminAction("tracking.version.submit", "module-version", versionId.toString(), Map.of());
    return view(found(versionId), staff);
  }

  @PostMapping("/admin/v1/tracking/versions/{versionId}/review")
  VersionView review(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID versionId,
      @Valid @RequestBody ReviewRequest r) {
    var staff = gate.require(token, true);
    modules.review(
        versionId, r.approve(), r.comments() == null ? null : r.comments().trim(), staff);
    audit.adminAction(
        r.approve() ? "tracking.version.approve" : "tracking.version.return",
        "module-version",
        versionId.toString(),
        r.comments(),
        Map.of());
    return view(found(versionId), staff);
  }

  @PostMapping("/admin/v1/tracking/versions/{versionId}/publication")
  VersionView publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, true);
    modules.publish(versionId, staff);
    audit.adminAction("tracking.version.publish", "module-version", versionId.toString(), Map.of());
    return view(found(versionId), staff);
  }

  @PostMapping("/admin/v1/tracking/versions/{versionId}/retirement")
  VersionView retire(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, true);
    modules.retire(versionId, staff);
    audit.adminAction("tracking.version.retire", "module-version", versionId.toString(), Map.of());
    return view(found(versionId), staff);
  }

  // --- Shapes -----------------------------------------------------------------------------------

  private ModuleStore.Version found(UUID id) {
    return store.version(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private static ModuleStore.Rule rule(UUID id, UUID versionId, RuleRequest r) {
    return new ModuleStore.Rule(
        id,
        versionId,
        r.code(),
        r.conditions(),
        r.unless() == null ? ModuleStore.JSON.createArrayNode() : r.unless(),
        r.outcome(),
        r.specialtyCodes() == null ? List.of() : r.specialtyCodes(),
        r.messageEn().trim(),
        r.messageFr().trim(),
        r.cooldownDays(),
        r.repeatWithinDays(),
        r.position() == null ? 0 : r.position());
  }

  private VersionView view(ModuleStore.Version v, StaffGate.Staff staff) {
    var module = store.module(v.moduleId()).orElseThrow();
    var bound = modules.bound(v.id());
    var readiness = modules.readiness(v);
    var d = v.details();
    List<BindingView> elements = new ArrayList<>();
    for (var b : store.bindings(v.id())) {
      library
          .byId(b.elementId())
          .ifPresent(
              e ->
                  elements.add(
                      new BindingView(
                          e.code(), e.version(), b.required(), b.position(), Group.of(b))));
    }
    return new VersionView(
        v.id(),
        v.moduleId(),
        module.code(),
        module.accentColour(),
        v.number(),
        v.status(),
        new Text(d.nameEn(), d.nameFr()),
        new Text(d.descriptionEn(), d.descriptionFr()),
        new Text(d.audienceEn(), d.audienceFr()),
        new Text(d.imageAltEn(), d.imageAltFr()),
        d.imageAssetId(),
        d.minAge(),
        d.enrolmentQuestion(),
        store.questions(v.id()),
        d.presentation(),
        d.reminderDefault(),
        elements,
        store.rules(v.id()).stream()
            .map(
                r ->
                    new RuleView(
                        r.id(),
                        r.code(),
                        r.conditions(),
                        r.unless(),
                        r.outcome(),
                        r.specialtyCodes(),
                        r.messageEn(),
                        r.messageFr(),
                        r.cooldownDays(),
                        r.repeatWithinDays(),
                        r.position(),
                        RuleRules.problems(
                            r.conditions(), r.unless(), bound, models.all(), questions.all())))
            .toList(),
        store.reviews(v.id()).stream()
            .map(x -> new ReviewView(x.reviewerName(), x.decision(), x.comments(), x.decidedAt()))
            .toList(),
        readiness.problems(),
        readiness.warnings(),
        v.draftedByName(),
        v.approvedByName(),
        staff.clinicalReviewer()
            && !staff.id().equals(v.draftedBy())
            && v.status().equals("IN_REVIEW")
            && v.approvedBy() == null,
        v.publishedAt());
  }

  /** Card images for the app and the portal (public: not personal data). */
  @RestController
  static class Images {
    private final ImageAssets images;

    Images(ImageAssets images) {
      this.images = images;
    }

    @GetMapping("/v1/tracking/images/{imageId}")
    ResponseEntity<byte[]> image(
        @PathVariable UUID imageId, @RequestParam(defaultValue = "true") boolean small) {
      var stored = images.read(imageId, small);
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(stored.contentType()))
          .cacheControl(
              org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(30))
                  .cachePublic())
          .body(stored.bytes());
    }
  }
}
