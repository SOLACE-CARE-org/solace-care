package care.solace.core.content;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content for staff (admin screens 15 and 16; tag admin-content; D44). Content editors write and
 * publish; clinical reviewers check crisis resources. Every change is audited.
 */
@RestController
class AdminContentController {

  private static final String AREA = "content";

  private final StaffAccess access;
  private final Contents contents;
  private final AuditLog audit;

  AdminContentController(StaffAccess access, Contents contents, AuditLog audit) {
    this.access = access;
    this.contents = contents;
    this.audit = audit;
  }

  record Row(
      UUID id,
      String slug,
      String type,
      String title,
      boolean enDone,
      boolean frDone,
      String clinical,
      String status,
      int version,
      Instant updatedAt) {}

  record Dial(
      @NotBlank @Pattern(regexp = "police|ambulance|fire|helpline") String service,
      @NotBlank @Pattern(regexp = "^[0-9+ ]{2,20}$") String number) {}

  record Draft(
      @NotNull @Size(max = 200) String titleEn,
      @NotNull @Size(max = 40000) String bodyEn,
      @NotNull @Size(max = 200) String titleFr,
      @NotNull @Size(max = 40000) String bodyFr,
      @Size(max = 10) List<@Valid Dial> numbers) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      UUID id,
      String slug,
      String type,
      String country,
      Draft draft,
      Draft published,
      boolean enDone,
      boolean frDone,
      String clinical,
      String clinicalBy,
      String status,
      int version,
      boolean canPublish,
      boolean canApprove,
      String editedBy,
      Instant updatedAt) {}

  record NewItem(
      @NotBlank @Pattern(regexp = "CRISIS_RESOURCE|ARTICLE|FAQ|LEGAL") String type,
      @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 60) String slug,
      @Pattern(regexp = "^[A-Z]{2}$") String country) {}

  record Done(@NotBlank @Pattern(regexp = "en|fr") String language, @NotNull Boolean done) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/admin/v1/content")
  List<Row> list(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) @Pattern(regexp = "CRISIS_RESOURCE|ARTICLE|FAQ|LEGAL")
          String type,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    boolean fr = "fr".equals(locale.getLanguage());
    return contents.all(type).stream()
        .map(
            i -> {
              String own = fr ? i.draft().titleFr() : i.draft().titleEn();
              String other = fr ? i.draft().titleEn() : i.draft().titleFr();
              return new Row(
                  i.id(),
                  i.slug(),
                  i.type(),
                  own.isBlank() ? (other.isBlank() ? i.slug() : other) : own,
                  i.enDone(),
                  i.frDone(),
                  i.clinical(),
                  Contents.status(i),
                  i.version(),
                  i.updatedAt());
            })
        .toList();
  }

  @PostMapping("/admin/v1/content")
  @ResponseStatus(HttpStatus.CREATED)
  Item create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody NewItem request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var item = contents.create(request.type(), request.slug(), request.country());
    audit.adminAction("content.create", "content", item.id().toString(), Map.of());
    return view(item, staff);
  }

  @GetMapping("/admin/v1/content/{contentId}")
  Item one(@AuthenticationPrincipal Jwt token, @PathVariable UUID contentId) {
    var staff = access.require(token, AREA, Level.VIEW);
    return view(contents.item(contentId), staff);
  }

  @PutMapping("/admin/v1/content/{contentId}")
  Item save(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID contentId,
      @Valid @RequestBody Draft draft) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var item = contents.save(contentId, text(draft), staff);
    audit.adminAction("content.edit", "content", contentId.toString(), Map.of());
    return view(item, staff);
  }

  @PutMapping("/admin/v1/content/{contentId}/translation-status")
  Item done(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID contentId,
      @Valid @RequestBody Done request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var item = contents.markDone(contentId, request.language(), request.done());
    audit.adminAction(
        "content.translation",
        "content",
        contentId.toString(),
        Map.of("language", request.language(), "done", String.valueOf(request.done())));
    return view(item, staff);
  }

  @PostMapping("/admin/v1/content/{contentId}/clinical-check")
  Item requestCheck(@AuthenticationPrincipal Jwt token, @PathVariable UUID contentId) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var item = contents.requestCheck(contentId, staff);
    audit.adminAction("content.clinical.request", "content", contentId.toString(), Map.of());
    return view(item, staff);
  }

  /** Clinical reviewers have view access to Content; the approval is their own step. */
  @PostMapping("/admin/v1/content/{contentId}/clinical-approval")
  Item approve(@AuthenticationPrincipal Jwt token, @PathVariable UUID contentId) {
    var staff = access.require(token, AREA, Level.VIEW);
    var item = contents.approveCheck(contentId, staff);
    audit.adminAction("content.clinical.approve", "content", contentId.toString(), Map.of());
    return view(item, staff);
  }

  @PostMapping("/admin/v1/content/{contentId}/publication")
  Item publish(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID contentId,
      @Valid @RequestBody Reason request) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var item = contents.publish(contentId, request.reason().trim(), staff);
    audit.adminAction(
        "content.publish",
        "content",
        contentId.toString(),
        request.reason().trim(),
        Map.of("version", String.valueOf(item.version())));
    return view(item, staff);
  }

  private static ContentStore.Text text(Draft d) {
    return new ContentStore.Text(
        d.titleEn().trim(),
        d.bodyEn().trim(),
        d.titleFr().trim(),
        d.bodyFr().trim(),
        d.numbers() == null
            ? List.of()
            : d.numbers().stream()
                .map(n -> new ContentStore.Dial(n.service(), n.number().trim()))
                .toList());
  }

  private static Draft draft(ContentStore.Text t) {
    return new Draft(
        t.titleEn(),
        t.bodyEn(),
        t.titleFr(),
        t.bodyFr(),
        t.numbers().stream().map(n -> new Dial(n.service(), n.number())).toList());
  }

  private Item view(ContentStore.Item i, StaffAccess.Staff staff) {
    return new Item(
        i.id(),
        i.slug(),
        i.type(),
        i.country(),
        draft(i.draft()),
        contents.published(i.id()).map(v -> draft(v.text())).orElse(null),
        i.enDone(),
        i.frDone(),
        i.clinical(),
        i.clinicalByName(),
        Contents.status(i),
        i.version(),
        Contents.canPublish(i),
        Contents.canApprove(i, staff)
            && (i.clinical().equals("NEEDED") || i.clinical().equals("REQUESTED")),
        i.editedByName(),
        i.updatedAt());
  }
}
