package care.solace.core.specialists;

import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/v1/specialist-applications} and {@code /admin/v1/specialists}
 * (contracts/openapi/core.yaml, tag admin-specialists). Every call checks the permission matrix
 * here, on the server: viewing needs V on Specialists, changing needs F, reinstating needs a Super
 * admin (D26).
 */
@RestController
class AdminReviewController {

  static final String AREA = "specialists";

  private final StaffAccess access;
  private final Reviews reviews;

  AdminReviewController(StaffAccess access, Reviews reviews) {
    this.access = access;
    this.reviews = reviews;
  }

  // --- Bodies, as in the contract --------------------------------------------------------------

  record ItemVerification(@NotNull Boolean verified, LocalDate licenceValidUntil) {}

  record LanguageVerification(
      @NotNull @Pattern(regexp = "NATIVE|FLUENT|PROFESSIONAL|BELOW_PROFESSIONAL")
          String verifiedLevel) {}

  record Bio(
      @NotNull @Pattern(regexp = "en|fr") String language,
      @NotBlank @Size(max = 600) String text) {}

  record Notes(@NotNull @Size(max = 4000) String notes) {}

  record Interview(@NotNull Instant at) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  // --- Views, as in the contract ---------------------------------------------------------------

  record StaffRef(String id, String name) {}

  record Verification(StaffRef by, Instant at) {}

  record Queue(Counts counts, List<QueueItem> items) {}

  record Counts(int submitted, int underReview, int changesRequested) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record QueueItem(
      UUID id,
      String fullName,
      String profession,
      List<DeclaredLanguage> languages,
      String status,
      Instant submittedAt,
      StaffRef assignedTo) {}

  record DeclaredLanguage(String code, String proficiency) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ApplicationReview(
      UUID id,
      String status,
      Boolean changesRequested,
      String fullName,
      String profession,
      String professionLabel,
      Instant submittedAt,
      Instant decidedAt,
      StaffRef assignedTo,
      Instant interviewAt,
      String reviewNote,
      Verification approvalProposal,
      int verifiedItems,
      Identity identity,
      Licence licence,
      List<LanguageItem> languages,
      Services services,
      Profile profile,
      Payout payout,
      List<Document> documents,
      String notes,
      List<ActivityItem> activity) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Identity(Verification verification) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Licence(
      String licenceNumber,
      String licensingBody,
      Integer yearsInPractice,
      LocalDate validUntil,
      Verification verification) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record LanguageItem(
      String code, String declared, String verifiedLevel, Verification verification) {}

  record Services(List<Offering> offerings, boolean withinBands) {}

  record Offering(String modality, boolean enabled, int durationMinutes, long price) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Profile(
      List<String> focusAreas,
      String bioEn,
      String bioFr,
      String draftedLanguage,
      Verification verification) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Payout(String method, String phone, String holderName, Verification verification) {}

  record Document(
      UUID id, String kind, String fileName, String contentType, long size, Instant uploadedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ActivityItem(Instant at, String kind, String subject, String value, StaffRef by) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SpecialistSummary(
      UUID id, String fullName, String professionLabel, String licenceNumber, String status) {}

  // --- Applications ----------------------------------------------------------------------------

  @GetMapping("/admin/v1/specialist-applications")
  Queue queue(
      @AuthenticationPrincipal Jwt token,
      @RequestParam String tab,
      @RequestParam(required = false) String profession,
      @RequestParam(required = false) String language,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    var queue = reviews.queue(parse(ReviewStore.Tab.class, tab), profession, language, locale);
    return new Queue(
        new Counts(
            queue.counts().getOrDefault(ReviewStore.Tab.SUBMITTED, 0),
            queue.counts().getOrDefault(ReviewStore.Tab.UNDER_REVIEW, 0),
            queue.counts().getOrDefault(ReviewStore.Tab.CHANGES_REQUESTED, 0)),
        queue.items().stream()
            .map(
                i ->
                    new QueueItem(
                        i.row().id(),
                        i.row().fullName(),
                        i.professionLabel(),
                        i.languages().stream()
                            .map(l -> new DeclaredLanguage(l.code(), l.proficiency()))
                            .toList(),
                        i.row().status().name(),
                        i.row().submittedAt(),
                        staff(i.row().assignedTo())))
            .toList());
  }

  @GetMapping("/admin/v1/specialist-applications/{id}")
  ApplicationReview review(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    return render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialist-applications/{id}/review")
  ApplicationReview open(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    reviews.openForReview(id, changer(token));
    return render(reviews.review(id, locale));
  }

  @PutMapping("/admin/v1/specialist-applications/{id}/items/{item}")
  ApplicationReview item(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String item,
      @Valid @RequestBody ItemVerification body,
      Locale locale) {
    reviews.verifyItem(
        id,
        parse(ReviewStore.Item.class, item),
        body.verified(),
        body.licenceValidUntil(),
        changer(token));
    return render(reviews.review(id, locale));
  }

  @PutMapping("/admin/v1/specialist-applications/{id}/languages/{code}")
  ApplicationReview language(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String code,
      @Valid @RequestBody LanguageVerification body,
      Locale locale) {
    reviews.verifyLanguage(id, code, body.verifiedLevel(), changer(token));
    return render(reviews.review(id, locale));
  }

  @PutMapping("/admin/v1/specialist-applications/{id}/bio")
  ApplicationReview bio(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Bio body,
      Locale locale) {
    reviews.bio(id, body.language(), body.text(), changer(token));
    return render(reviews.review(id, locale));
  }

  @PutMapping("/admin/v1/specialist-applications/{id}/notes")
  ApplicationReview notes(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Notes body,
      Locale locale) {
    changer(token);
    reviews.notes(id, body.notes());
    return render(reviews.review(id, locale));
  }

  @PutMapping("/admin/v1/specialist-applications/{id}/interview")
  ApplicationReview interview(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Interview body,
      Locale locale) {
    reviews.interview(id, body.at(), changer(token));
    return render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialist-applications/{id}/change-request")
  ApplicationReview changes(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body,
      Locale locale) {
    reviews.requestChanges(id, body.reason(), changer(token));
    return render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialist-applications/{id}/approval-proposal")
  ApplicationReview proposeApproval(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    reviews.proposeApproval(id, changer(token), locale);
    return render(reviews.review(id, locale));
  }

  @DeleteMapping("/admin/v1/specialist-applications/{id}/approval-proposal")
  ApplicationReview withdrawApproval(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    reviews.withdrawApproval(id, changer(token));
    return render(reviews.review(id, locale));
  }

  /** Confirms a proposed approval; only someone other than the proposer (D29). */
  @PostMapping("/admin/v1/specialist-applications/{id}/approval")
  ApplicationReview approve(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    reviews.approve(id, changer(token), locale);
    return render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialist-applications/{id}/rejection")
  ApplicationReview reject(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body,
      Locale locale) {
    reviews.reject(id, body.reason(), changer(token));
    return render(reviews.review(id, locale));
  }

  @GetMapping("/admin/v1/specialist-applications/{id}/documents/{fileId}")
  ResponseEntity<byte[]> document(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, @PathVariable UUID fileId) {
    access.require(token, AREA, Level.VIEW);
    var file = reviews.open(id, fileId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        // Credentials must not linger in shared caches or the browser's.
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(file.bytes());
  }

  // --- Specialists -----------------------------------------------------------------------------

  @GetMapping("/admin/v1/specialists")
  List<SpecialistSummary> find(
      @AuthenticationPrincipal Jwt token, @RequestParam String query, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    if (query.length() > 120) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return reviews.search(query).stream()
        .map(id -> reviews.review(id, locale))
        .map(
            r ->
                new SpecialistSummary(
                    r.profile().accountId(),
                    r.profile().fullName(),
                    r.professionLabel(),
                    r.profile().licenceNumber(),
                    r.profile().status().name()))
        .toList();
  }

  @GetMapping("/admin/v1/specialists/{id}")
  ApplicationReview record(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    return render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialists/{id}/suspension")
  ApplicationReview suspend(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body,
      Locale locale) {
    reviews.suspend(id, body.reason(), changer(token));
    return render(reviews.review(id, locale));
  }

  @DeleteMapping("/admin/v1/specialists/{id}/suspension")
  ApplicationReview reinstate(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID id, Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    if (!staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    reviews.reinstate(id, new ReviewStore.StaffRef(staff.id(), staff.name()));
    return render(reviews.review(id, locale));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private ReviewStore.StaffRef changer(Jwt token) {
    var staff = access.require(token, AREA, Level.CHANGE);
    return new ReviewStore.StaffRef(staff.id(), staff.name());
  }

  private static ApplicationReview render(Reviews.Review r) {
    var p = r.profile();
    var items = r.items();
    return new ApplicationReview(
        p.accountId(),
        p.status().name(),
        r.state().changesRequestedAt() != null ? Boolean.TRUE : null,
        p.fullName(),
        p.profession(),
        r.professionLabel(),
        p.submittedAt(),
        r.state().decidedAt(),
        staff(r.state().assignedTo()),
        p.interviewAt(),
        p.reviewNote(),
        verification(r.state().approvalProposal()),
        r.verifiedItems(),
        new Identity(verification(items.get(ReviewStore.Item.IDENTITY))),
        new Licence(
            p.licenceNumber(),
            p.licensingBody(),
            p.yearsExperience(),
            r.state().licenceValidUntil(),
            verification(items.get(ReviewStore.Item.LICENCE))),
        r.languages().stream()
            .map(
                l ->
                    new LanguageItem(
                        l.code(), l.declared(), l.verifiedLevel(), verification(l.verification())))
            .toList(),
        new Services(
            r.offerings().stream()
                .map(o -> new Offering(o.modality(), o.enabled(), o.durationMinutes(), o.price()))
                .toList(),
            r.withinBands()),
        new Profile(
            r.focusAreas(),
            p.bioEn(),
            p.bioFr(),
            r.state().bioDraftedLanguage(),
            verification(items.get(ReviewStore.Item.PROFILE))),
        r.payout()
            .map(
                o ->
                    new Payout(
                        o.method(),
                        ApplicationController.mask(o.phone()),
                        o.holderName(),
                        verification(items.get(ReviewStore.Item.PAYOUT))))
            .orElse(new Payout(null, null, null, null)),
        r.documents().stream()
            .map(
                f ->
                    new Document(
                        f.id(), f.kind(), f.fileName(), f.contentType(), f.size(), f.uploadedAt()))
            .toList(),
        r.state().staffNotes() == null ? "" : r.state().staffNotes(),
        r.activity().stream()
            .map(a -> new ActivityItem(a.at(), a.kind(), a.subject(), a.value(), staff(a.by())))
            .toList());
  }

  /** An enum from a path or query value; 400 rather than a framework error when it is not one. */
  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
  }

  private static StaffRef staff(ReviewStore.StaffRef ref) {
    return ref == null ? null : new StaffRef(ref.id(), ref.name());
  }

  private static Verification verification(ReviewStore.Verification v) {
    return v == null ? null : new Verification(staff(v.by()), v.at());
  }
}
