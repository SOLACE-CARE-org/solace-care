package care.solace.core.specialists;

import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff checks of a specialist's professions and specialties, and answers to specialty requests
 * (contracts/openapi/core.yaml; D62). Checking and approving needs F on Specialists; answering a
 * request changes the catalogue, so it needs F on the Catalogue. Core checks the matrix here.
 */
@RestController
class AdminPracticeController {

  private static final String CATALOGUE = "catalogue";
  private static final List<String> REQUEST_STATUSES =
      List.of("OPEN", "CREATED", "MATCHED", "DECLINED", "WITHDRAWN");

  private final StaffAccess access;
  private final Reviews reviews;
  private final PracticeReviews practice;
  private final SpecialtyRequests requests;

  AdminPracticeController(
      StaffAccess access, Reviews reviews, PracticeReviews practice, SpecialtyRequests requests) {
    this.access = access;
    this.reviews = reviews;
    this.practice = practice;
    this.requests = requests;
  }

  record LicenceVerification(@NotNull Boolean verified, LocalDate licenceValidUntil) {}

  record Verified(@NotNull Boolean verified) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  record Creation(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 80) String labelEn,
      @NotBlank @Size(max = 80) String labelFr,
      @NotBlank @Size(max = 40) String parentCode,
      @NotBlank @Size(max = 500) String reason) {}

  record Match(
      @NotBlank @Size(max = 40) String specialty, @NotBlank @Size(max = 500) String reason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Addition(
      UUID specialistId,
      String fullName,
      String kind,
      String profession,
      String professionLabel,
      String specialty,
      String specialtyLabel,
      AdminReviewController.Verification verification,
      Instant requestedAt) {}

  // --- Professions and specialties -------------------------------------------------------------

  @PutMapping("/admin/v1/specialists/{id}/professions/{profession}/verification")
  AdminReviewController.ApplicationReview verifyProfession(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String profession,
      @Valid @RequestBody LicenceVerification body,
      Locale locale) {
    practice.verifyProfession(
        id, profession, body.verified(), body.licenceValidUntil(), changer(token));
    return AdminReviewController.render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialists/{id}/professions/{profession}/approval")
  AdminReviewController.ApplicationReview approveProfession(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String profession,
      Locale locale) {
    practice.approveProfession(id, profession, changer(token));
    return AdminReviewController.render(reviews.review(id, locale));
  }

  @PostMapping("/admin/v1/specialists/{id}/professions/{profession}/rejection")
  AdminReviewController.ApplicationReview rejectProfession(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String profession,
      @Valid @RequestBody Reason body,
      Locale locale) {
    practice.rejectProfession(id, profession, body.reason(), changer(token));
    return AdminReviewController.render(reviews.review(id, locale));
  }

  @PutMapping(
      "/admin/v1/specialists/{id}/professions/{profession}/specialties/{specialty}/verification")
  AdminReviewController.ApplicationReview verifySpecialty(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String profession,
      @PathVariable String specialty,
      @Valid @RequestBody Verified body,
      Locale locale) {
    practice.verifySpecialty(id, profession, specialty, body.verified(), changer(token));
    return AdminReviewController.render(reviews.review(id, locale));
  }

  @PostMapping(
      "/admin/v1/specialists/{id}/professions/{profession}/specialties/{specialty}/rejection")
  AdminReviewController.ApplicationReview declineSpecialty(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @PathVariable String profession,
      @PathVariable String specialty,
      @Valid @RequestBody Reason body,
      Locale locale) {
    practice.declineSpecialty(id, profession, specialty, body.reason(), changer(token));
    return AdminReviewController.render(reviews.review(id, locale));
  }

  @GetMapping("/admin/v1/specialist-additions")
  List<Addition> additions(@AuthenticationPrincipal Jwt token, Locale locale) {
    access.require(token, AdminReviewController.AREA, Level.VIEW);
    return practice.additions(locale).stream()
        .map(
            a ->
                new Addition(
                    a.row().specialistId(),
                    a.row().fullName(),
                    a.row().kind(),
                    a.row().profession(),
                    a.professionLabel(),
                    a.row().specialty(),
                    a.specialtyLabel(),
                    a.row().verification() == null || a.row().verification().by() == null
                        ? null
                        : new AdminReviewController.Verification(
                            new AdminReviewController.StaffRef(
                                a.row().verification().by().id(),
                                a.row().verification().by().name()),
                            a.row().verification().at()),
                    a.row().requestedAt()))
        .toList();
  }

  // --- Specialty requests ----------------------------------------------------------------------

  /** Staff who see Specialists or the Catalogue may look. */
  @GetMapping("/admin/v1/specialty-requests")
  List<AdminReviewController.SpecialtyRequestView> requests(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) String status,
      Locale locale) {
    if (!access.allows(token, AdminReviewController.AREA, Level.VIEW)) {
      access.require(token, CATALOGUE, Level.VIEW);
    }
    String wanted = status == null || status.isBlank() ? "OPEN" : status;
    if (!REQUEST_STATUSES.contains(wanted)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return requests.list(wanted, locale).stream().map(AdminPracticeController::view).toList();
  }

  @PostMapping("/admin/v1/specialty-requests/{requestId}/creation")
  AdminReviewController.SpecialtyRequestView create(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID requestId,
      @Valid @RequestBody Creation body,
      Locale locale) {
    requests.create(
        requestId,
        body.code().strip(),
        body.labelEn(),
        body.labelFr(),
        body.parentCode().strip(),
        body.reason(),
        cataloguer(token));
    return view(requests.one(requestId, locale));
  }

  @PostMapping("/admin/v1/specialty-requests/{requestId}/match")
  AdminReviewController.SpecialtyRequestView match(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID requestId,
      @Valid @RequestBody Match body,
      Locale locale) {
    requests.match(requestId, body.specialty().strip(), body.reason(), cataloguer(token));
    return view(requests.one(requestId, locale));
  }

  @PostMapping("/admin/v1/specialty-requests/{requestId}/decline")
  AdminReviewController.SpecialtyRequestView decline(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID requestId,
      @Valid @RequestBody Reason body,
      Locale locale) {
    requests.decline(requestId, body.reason(), cataloguer(token));
    return view(requests.one(requestId, locale));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private ReviewStore.StaffRef changer(Jwt token) {
    var staff = access.require(token, AdminReviewController.AREA, Level.CHANGE);
    return new ReviewStore.StaffRef(staff.id(), staff.name());
  }

  private ReviewStore.StaffRef cataloguer(Jwt token) {
    var staff = access.require(token, CATALOGUE, Level.CHANGE);
    return new ReviewStore.StaffRef(staff.id(), staff.name());
  }

  private static AdminReviewController.SpecialtyRequestView view(SpecialtyRequests.Listed l) {
    return AdminReviewController.request(
        l.row(), l.fullName(), l.professionLabel(), l.specialtyLabel());
  }
}
