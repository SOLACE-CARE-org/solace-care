package care.solace.tracking.elements;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.elements.ElementRules.Draft;
import care.solace.tracking.modules.StaffGate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
 * The element builder (admin screens 32 and 33, provisional; tag admin-elements; D61). Every change
 * is audited: who drafted, who approved, who published or retired.
 */
@RestController
class AdminElementsController {

  /** Labels, constraints and props are small; anything bigger is not an element. */
  static final int MAX_JSON = 8000;

  private final StaffGate gate;
  private final ElementDrafts drafts;
  private final InputViews views;
  private final AuditLog audit;

  AdminElementsController(StaffGate gate, ElementDrafts drafts, InputViews views, AuditLog audit) {
    this.gate = gate;
    this.drafts = drafts;
    this.views = views;
    this.audit = audit;
  }

  record InputViewView(
      String key,
      List<ValueType> valueTypes,
      List<Kind> kinds,
      JsonNode props,
      String minAppVersion) {}

  record ReviewView(String reviewer, String decision, String comments, Instant decidedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ElementVersionView(
      UUID id,
      String code,
      String version,
      String status,
      Kind kind,
      ValueType valueType,
      JsonNode constraints,
      String inputView,
      JsonNode viewProps,
      String icon,
      List<String> signals,
      JsonNode labels,
      String minAppVersion,
      List<String> problems,
      Integer boundBy,
      List<ReviewView> reviews,
      String draftedBy,
      String approvedBy,
      boolean canApprove,
      Instant publishedAt) {}

  record DraftRequest(
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") @Size(max = 40) String code,
      @NotNull Kind kind,
      @NotNull ValueType valueType,
      @NotNull JsonNode constraints,
      @NotNull @Size(max = 40) String inputView,
      JsonNode viewProps,
      @NotNull @Size(max = 40) String icon,
      @NotNull JsonNode labels) {}

  record ReviewRequest(@NotNull Boolean approve, @Size(max = 4000) String comments) {}

  @GetMapping("/admin/v1/tracking/input-views")
  List<InputViewView> inputViews(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return views.all().stream()
        .map(
            v ->
                new InputViewView(v.key(), v.valueTypes(), v.kinds(), v.props(), v.minAppVersion()))
        .toList();
  }

  @GetMapping("/admin/v1/tracking/element-icons")
  List<String> icons(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return List.copyOf(ElementRules.ICONS);
  }

  @GetMapping("/admin/v1/tracking/element-versions")
  List<ElementVersionView> list(@AuthenticationPrincipal Jwt token) {
    var staff = gate.require(token, false);
    return drafts.all().stream().map(v -> view(v, staff, false)).toList();
  }

  @PostMapping("/admin/v1/tracking/element-versions")
  @ResponseStatus(HttpStatus.CREATED)
  ElementVersionView create(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.create(draft(request), staff);
    audit.adminAction(
        "tracking.element.create",
        "element-version",
        v.id().toString(),
        Map.of("code", request.code(), "version", v.number()));
    return view(v, staff, true);
  }

  @GetMapping("/admin/v1/tracking/element-versions/{id}")
  ElementVersionView version(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, false);
    return view(found(id), staff, true);
  }

  @PutMapping("/admin/v1/tracking/element-versions/{id}")
  ElementVersionView save(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody DraftRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.save(id, draft(request), staff);
    audit.adminAction(
        "tracking.element.save", "element-version", id.toString(), Map.of("version", v.number()));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/element-versions/{id}/submission")
  ElementVersionView submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.submit(id, staff);
    audit.adminAction("tracking.element.submit", "element-version", id.toString(), Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/element-versions/{id}/review")
  ElementVersionView review(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewRequest request) {
    var staff = gate.require(token, true);
    var v = drafts.review(id, request.approve(), request.comments(), staff);
    audit.adminAction(
        request.approve() ? "tracking.element.approve" : "tracking.element.return",
        "element-version",
        id.toString(),
        Map.of());
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/element-versions/{id}/publication")
  ElementVersionView publish(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.publish(id, staff);
    audit.adminAction(
        "tracking.element.publish",
        "element-version",
        id.toString(),
        Map.of("code", v.draft().code(), "version", v.number()));
    return view(v, staff, true);
  }

  @PostMapping("/admin/v1/tracking/element-versions/{id}/retirement")
  ElementVersionView retire(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var v = drafts.retire(id, staff);
    audit.adminAction(
        "tracking.element.retire",
        "element-version",
        id.toString(),
        Map.of("code", v.draft().code(), "version", v.number()));
    return view(v, staff, true);
  }

  private ElementDrafts.Version found(UUID id) {
    return drafts.version(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /** The request as a draft; shapes the form cannot fix are refused, the rest become problems. */
  static Draft draft(DraftRequest r) {
    JsonNode labels = r.labels();
    boolean fine =
        r.constraints().isObject()
            && labels.isObject()
            && labels.path("en").isObject()
            && labels.path("fr").isObject()
            && (r.viewProps() == null || r.viewProps().isNull() || r.viewProps().isObject())
            && size(r.constraints()) + size(labels) + size(r.viewProps()) <= MAX_JSON;
    if (!fine) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return new Draft(
        r.code(),
        r.kind(),
        r.valueType(),
        r.constraints(),
        r.inputView().trim(),
        r.viewProps() == null || r.viewProps().isNull()
            ? JsonNodeFactory.instance.objectNode()
            : r.viewProps(),
        r.icon().trim(),
        labels);
  }

  private static int size(JsonNode node) {
    return node == null ? 0 : node.toString().length();
  }

  /**
   * @param full with problems, reviews and bindings; the list leaves them out
   */
  private ElementVersionView view(ElementDrafts.Version v, StaffGate.Staff staff, boolean full) {
    var d = v.draft();
    boolean canApprove =
        staff.clinicalReviewer()
            && v.status().equals("IN_REVIEW")
            && v.approvedBy() == null
            && !staff.id().equals(v.draftedBy());
    return new ElementVersionView(
        v.id(),
        d.code(),
        v.number(),
        v.status(),
        d.kind(),
        d.valueType(),
        d.constraints(),
        d.inputView(),
        d.viewProps(),
        d.icon(),
        v.signals(),
        d.labels(),
        v.minAppVersion(),
        v.open() ? drafts.problems(v) : List.of(),
        full ? drafts.boundBy(v.id()) : null,
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
