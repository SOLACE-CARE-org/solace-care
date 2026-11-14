package care.solace.tracking.context;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.context.ContextQuestion.Option;
import care.solace.tracking.context.ContextQuestion.Scope;
import care.solace.tracking.context.ContextQuestion.Type;
import care.solace.tracking.modules.StaffGate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * The context question builder (admin screen 34, provisional; tag admin-context-questions; D61).
 * Every change is audited: who drafted, who approved, who published or retired.
 */
@RestController
class AdminContextQuestionsController {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CODE = "^[a-z][a-z0-9_]*$";

  private final StaffGate gate;
  private final ContextQuestionDrafts drafts;
  private final AuditLog audit;

  AdminContextQuestionsController(StaffGate gate, ContextQuestionDrafts drafts, AuditLog audit) {
    this.gate = gate;
    this.drafts = drafts;
    this.audit = audit;
  }

  record OptionRequest(
      @NotBlank @Pattern(regexp = CODE) @Size(max = 40) String code, @NotNull JsonNode labels) {}

  record DraftRequest(
      @NotBlank @Pattern(regexp = CODE) @Size(max = 40) String code,
      @NotNull Type type,
      @NotNull Scope scope,
      @NotNull JsonNode question,
      @NotNull @Size(max = ContextQuestionRules.MAX_OPTIONS) List<@Valid OptionRequest> options) {}

  record ReviewRequest(@NotNull Boolean approve, @Size(max = 4000) String comments) {}

  record ReviewView(String reviewer, String decision, String comments, Instant decidedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record QuestionVersionView(
      UUID id,
      String code,
      int version,
      String status,
      Type type,
      Scope scope,
      JsonNode question,
      List<Option> options,
      List<String> problems,
      Integer usedBy,
      List<ReviewView> reviews,
      String draftedBy,
      String approvedBy,
      boolean canApprove,
      Instant publishedAt) {}

  @GetMapping("/admin/v1/tracking/context-question-versions")
  List<QuestionVersionView> list(@AuthenticationPrincipal Jwt token) {
    var staff = gate.require(token, false);
    return drafts.all().stream().map(v -> view(v, staff, false)).toList();
  }

  @PostMapping("/admin/v1/tracking/context-question-versions")
  @ResponseStatus(HttpStatus.CREATED)
  QuestionVersionView create(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.create(question(request), staff);
    audit.adminAction(
        "tracking.context-question.create",
        "context-question-version",
        v.id().toString(),
        Map.of("code", request.code(), "version", String.valueOf(v.question().version())));
    return view(v, staff, true);
  }

  @GetMapping("/admin/v1/tracking/context-question-versions/{id}")
  QuestionVersionView version(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, false);
    return view(found(id), staff, true);
  }

  @PutMapping("/admin/v1/tracking/context-question-versions/{id}")
  QuestionVersionView save(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.save(id, question(request), staff);
    audit.adminAction(
        "tracking.context-question.save",
        "context-question-version",
        id.toString(),
        Map.of("version", String.valueOf(v.question().version())));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/context-question-versions/{id}/submission")
  QuestionVersionView submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.submit(id, staff);
    audit.adminAction(
        "tracking.context-question.submit", "context-question-version", id.toString(), Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/context-question-versions/{id}/review")
  QuestionVersionView review(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.review(id, request.approve(), request.comments(), staff);
    audit.adminAction(
        request.approve()
            ? "tracking.context-question.approve"
            : "tracking.context-question.return",
        "context-question-version",
        id.toString(),
        Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/context-question-versions/{id}/publication")
  QuestionVersionView publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.publish(id, staff);
    audit.adminAction(
        "tracking.context-question.publish",
        "context-question-version",
        id.toString(),
        Map.of("code", v.question().code(), "version", String.valueOf(v.question().version())));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/context-question-versions/{id}/retirement")
  QuestionVersionView retire(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.retire(id, staff);
    audit.adminAction(
        "tracking.context-question.retire",
        "context-question-version",
        id.toString(),
        Map.of("code", v.question().code(), "version", String.valueOf(v.question().version())));
    return view(v, staff, true);
  }

  private ContextQuestionDrafts.Version found(UUID id) {
    return drafts.version(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /**
   * The request as a question, with the wording in the stored shape. Shapes the form cannot fix are
   * refused; the rest become problems.
   */
  static ContextQuestion question(DraftRequest r) {
    boolean fine =
        r.question().isObject()
            && r.question().toString().length() <= 1000
            && r.options().stream().allMatch(o -> o.labels().isObject());
    if (!fine) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    ObjectNode labels = JSON.createObjectNode();
    for (String lang : List.of("en", "fr")) {
      labels.putObject(lang).set("question", r.question().path(lang).deepCopy());
    }
    // A yes/no question has no options; the form may still send the ones it had.
    List<Option> options =
        r.type() == Type.YES_NO
            ? List.of()
            : r.options().stream().map(o -> new Option(o.code(), o.labels())).toList();
    return new ContextQuestion(r.code(), 0, r.type(), r.scope(), labels, options);
  }

  /**
   * @param full with problems, reviews and use; the list leaves them out
   */
  private QuestionVersionView view(
      ContextQuestionDrafts.Version v, StaffGate.Staff staff, boolean full) {
    var q = v.question();
    boolean canApprove =
        staff.clinicalReviewer()
            && v.status().equals("IN_REVIEW")
            && v.approvedBy() == null
            && !staff.id().equals(v.draftedBy());
    ObjectNode question = JSON.createObjectNode();
    question.set("en", q.labels().path("en").path("question"));
    question.set("fr", q.labels().path("fr").path("question"));
    return new QuestionVersionView(
        v.id(),
        q.code(),
        q.version(),
        v.status(),
        q.type(),
        q.scope(),
        question,
        q.options(),
        v.open() ? drafts.problems(v) : List.of(),
        full ? drafts.usedBy(q.code()) : null,
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
