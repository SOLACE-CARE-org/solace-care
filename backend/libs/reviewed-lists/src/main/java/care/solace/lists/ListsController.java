package care.solace.lists;

import care.solace.audit.AuditLog;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * This service's reviewed lists for staff (D61): what is in force, every version, drafting, the
 * check, and a second clinician's sign-off. Every change is audited; entries are clinical wording
 * and test cases, never anyone's data.
 */
@RestController
class ListsController {

  private final ListAccess access;
  private final ReviewedLists lists;
  private final AuditLog audit;

  ListsController(ListAccess access, ReviewedLists lists, AuditLog audit) {
    this.access = access;
    this.lists = lists;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Summary(
      String code,
      String description,
      Integer liveVersion,
      int drafts,
      List<String> drafters,
      List<String> approvers) {}

  record ListView(
      String code, String description, JsonNode inForce, List<ReviewedLists.Version> versions) {}

  record Draft(@NotNull JsonNode entries, @NotBlank @Size(max = 500) String note) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Check(boolean passed, String problem) {}

  @GetMapping("/admin/v1/lists")
  List<Summary> all(@AuthenticationPrincipal Jwt token) {
    access.require(token);
    return lists.all().stream()
        .map(
            l -> {
              var versions = lists.versions(l.code());
              return new Summary(
                  l.code(),
                  l.description(),
                  lists.live(l.code()).map(ReviewedLists.Version::version).orElse(null),
                  (int)
                      versions.stream()
                          .filter(v -> v.status() == ReviewedLists.Status.DRAFT)
                          .count(),
                  List.copyOf(l.drafters()),
                  List.copyOf(l.approvers()));
            })
        .toList();
  }

  @GetMapping("/admin/v1/lists/{code}")
  ListView one(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    access.require(token);
    var list = lists.list(code);
    return new ListView(code, list.description(), lists.inForce(code), lists.versions(code));
  }

  @PostMapping("/admin/v1/lists/{code}/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  ReviewedLists.Version draft(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody Draft body) {
    var staff = access.require(token);
    var version = lists.draft(code, body.entries(), body.note(), staff);
    audit.adminAction(
        "lists.draft",
        "reviewed_list",
        code,
        body.note().strip(),
        Map.of("version", String.valueOf(version.version())));
    return version;
  }

  /** Runs the list's check on a draft, such as the safety set, without signing it off. */
  @PostMapping("/admin/v1/lists/{code}/drafts/{version}/check")
  Check check(
      @AuthenticationPrincipal Jwt token, @PathVariable String code, @PathVariable int version) {
    access.require(token);
    var problem = lists.check(code, version);
    return new Check(problem.isEmpty(), problem.orElse(null));
  }

  @PostMapping("/admin/v1/lists/{code}/drafts/{version}/sign-off")
  ReviewedLists.Version signOff(
      @AuthenticationPrincipal Jwt token, @PathVariable String code, @PathVariable int version) {
    var staff = access.require(token);
    var signed = lists.signOff(code, version, staff);
    audit.adminAction(
        "lists.sign-off", "reviewed_list", code, Map.of("version", String.valueOf(version)));
    return signed;
  }

  @PostMapping("/admin/v1/lists/{code}/drafts/{version}/decline")
  ReviewedLists.Version decline(
      @AuthenticationPrincipal Jwt token, @PathVariable String code, @PathVariable int version) {
    var staff = access.require(token);
    var declined = lists.decline(code, version, staff);
    audit.adminAction(
        "lists.decline", "reviewed_list", code, Map.of("version", String.valueOf(version)));
    return declined;
  }
}
