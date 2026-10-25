package care.solace.core.specialists;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /v1/me/specialist-application} (contracts/openapi/core.yaml, tag specialists). */
@RestController
class ApplicationController {

  private final Accounts accounts;
  private final Applications applications;

  ApplicationController(Accounts accounts, Applications applications) {
    this.accounts = accounts;
    this.applications = applications;
  }

  // --- Request bodies, as in the contract ------------------------------------------------------

  record PracticeBody(
      @NotBlank String profession,
      @NotBlank @Size(max = 60) String licenceNumber,
      @NotNull @Min(0) @Max(70) Integer yearsInPractice,
      @NotBlank @Size(max = 160) String licensingBody,
      @NotNull @Size(min = 1, max = Applications.MOST_FOCUS_AREAS) List<@NotNull String> focusAreas,
      @NotBlank @Size(max = 600) String bio) {}

  record LanguagesBody(
      @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid LanguageBody> languages) {}

  record LanguageBody(
      @NotBlank String code,
      @NotNull @Pattern(regexp = "NATIVE|FLUENT|PROFESSIONAL") String proficiency) {}

  record ServicesBody(
      @NotNull @Size(min = 1, max = 2) List<@NotNull @Valid OfferingBody> offerings) {}

  record OfferingBody(
      @NotNull @Pattern(regexp = "AUDIO|VIDEO") String modality,
      @NotNull Boolean enabled,
      @NotNull Integer durationMinutes,
      @NotNull @PositiveOrZero Long price) {}

  record PayoutBody(
      @NotNull @Pattern(regexp = "MTN_MOMO|ORANGE_MONEY|BANK_TRANSFER") String method,
      @Size(max = 20) String phone,
      @NotBlank @Size(max = 120) String holderName) {}

  // --- The application, as in the contract -----------------------------------------------------

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SpecialistApplication(
      String status,
      String fullName,
      Applications.Steps steps,
      PracticeView practice,
      List<LanguageView> languages,
      List<DocumentView> documents,
      List<OfferingBody> offerings,
      PayoutView payout,
      Pricing pricing,
      Instant submittedAt,
      Instant interviewAt,
      String reviewNote,
      DraftedBioView draftedBio) {}

  record DraftedBioView(String language, String text, boolean confirmed) {}

  record BioConfirmation(@Size(min = 1, max = 600) String text) {}

  record PracticeView(
      String profession,
      String licenceNumber,
      int yearsInPractice,
      String licensingBody,
      List<String> focusAreas,
      String bio) {}

  record LanguageView(String code, String proficiency, boolean verified) {}

  record DocumentView(String kind, List<FileView> files) {}

  record FileView(String fileName, String contentType, long size, Instant uploadedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PayoutView(String method, String phone, String holderName) {}

  record Pricing(long minPrice, long maxPrice, BigDecimal feePercent, List<Integer> durations) {}

  // --- Endpoints -------------------------------------------------------------------------------

  @GetMapping("/v1/me/specialist-application")
  SpecialistApplication get(@AuthenticationPrincipal Jwt token) {
    return render(applications.view(accounts.require(token)));
  }

  @PutMapping("/v1/me/specialist-application/practice")
  SpecialistApplication practice(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody PracticeBody body) {
    Account account = accounts.require(token);
    applications.savePractice(
        account,
        new Applications.Practice(
            body.profession(),
            body.licenceNumber(),
            body.yearsInPractice(),
            body.licensingBody(),
            body.focusAreas(),
            body.bio()));
    return render(applications.view(account));
  }

  @PutMapping("/v1/me/specialist-application/languages")
  SpecialistApplication languages(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody LanguagesBody body) {
    Account account = accounts.require(token);
    applications.saveLanguages(
        account,
        body.languages().stream()
            .map(l -> new ApplicationStore.Language(l.code(), l.proficiency(), null))
            .toList());
    return render(applications.view(account));
  }

  @PutMapping(
      path = "/v1/me/specialist-application/documents/{kind}",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  SpecialistApplication document(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String kind,
      @RequestPart("file") List<MultipartFile> files) {
    Account account = accounts.require(token);
    Documents.Kind parsed;
    try {
      parsed = Documents.Kind.valueOf(kind);
    } catch (IllegalArgumentException e) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    List<Documents.Upload> uploads = new ArrayList<>();
    for (MultipartFile file : files) {
      try {
        uploads.add(new Documents.Upload(file.getOriginalFilename(), file.getBytes()));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    applications.uploadDocuments(account, parsed, uploads);
    return render(applications.view(account));
  }

  @PutMapping("/v1/me/specialist-application/services")
  SpecialistApplication services(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody ServicesBody body) {
    Account account = accounts.require(token);
    applications.saveServices(
        account,
        body.offerings().stream()
            .map(
                o ->
                    new ApplicationStore.Offering(
                        o.modality(), o.enabled(), o.durationMinutes(), o.price()))
            .toList());
    return render(applications.view(account));
  }

  @PutMapping("/v1/me/specialist-application/payout")
  SpecialistApplication payout(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody PayoutBody body) {
    Account account = accounts.require(token);
    applications.savePayout(
        account, new ApplicationStore.Payout(body.method(), body.phone(), body.holderName()));
    return render(applications.view(account));
  }

  @PostMapping("/v1/me/specialist-application/bio-confirmation")
  SpecialistApplication confirmBio(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody BioConfirmation body) {
    Account account = accounts.require(token);
    applications.confirmBio(account, body.text());
    return render(applications.view(account));
  }

  @PostMapping("/v1/me/specialist-application/submission")
  SpecialistApplication submit(@AuthenticationPrincipal Jwt token) {
    Account account = accounts.require(token);
    applications.submit(account);
    return render(applications.view(account));
  }

  // --- Rendering -------------------------------------------------------------------------------

  private static SpecialistApplication render(Applications.View view) {
    var p = view.profile();
    PracticeView practice =
        p.practiceSavedAt() == null
            ? null
            : new PracticeView(
                p.profession(),
                p.licenceNumber(),
                p.yearsExperience(),
                p.licensingBody(),
                view.focusAreas(),
                p.bio());
    Map<String, List<FileView>> byKind = new LinkedHashMap<>();
    for (var file : view.documents()) {
      byKind
          .computeIfAbsent(file.kind(), k -> new ArrayList<>())
          .add(new FileView(file.fileName(), file.contentType(), file.size(), file.uploadedAt()));
    }
    var pricing = view.pricing();
    return new SpecialistApplication(
        p.status().name(),
        p.fullName(),
        view.steps(),
        practice,
        view.languages().stream()
            .map(l -> new LanguageView(l.code(), l.proficiency(), l.verifiedAt() != null))
            .toList(),
        byKind.entrySet().stream().map(e -> new DocumentView(e.getKey(), e.getValue())).toList(),
        view.offerings().stream()
            .map(o -> new OfferingBody(o.modality(), o.enabled(), o.durationMinutes(), o.price()))
            .toList(),
        view.payout()
            .map(o -> new PayoutView(o.method(), mask(o.phone()), o.holderName()))
            .orElse(null),
        new Pricing(
            pricing.minPrice(), pricing.maxPrice(), pricing.feePercent(), pricing.durations()),
        p.submittedAt(),
        p.interviewAt(),
        p.reviewNote(),
        view.draftedBio()
            .map(d -> new DraftedBioView(d.language(), d.text(), d.confirmedAt() != null))
            .orElse(null));
  }

  /** "+237677000000" becomes "+237•••••••00": enough to recognise, not to reuse. */
  static String mask(String phone) {
    if (phone == null) {
      return null;
    }
    int keepStart = phone.startsWith("+237") ? 4 : Math.min(3, phone.length());
    int keepEnd = Math.min(2, phone.length() - keepStart);
    return phone.substring(0, keepStart)
        + "•".repeat(phone.length() - keepStart - keepEnd)
        + phone.substring(phone.length() - keepEnd);
  }
}
