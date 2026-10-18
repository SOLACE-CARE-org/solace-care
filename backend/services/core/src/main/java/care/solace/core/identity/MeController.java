package care.solace.core.identity;

import care.solace.core.catalog.Catalogue;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's own account: {@code /v1/me} and {@code /v1/me/preferences}. */
@RestController
class MeController {

  private final Members members;
  private final AccountStore store;
  private final Catalogue catalogue;
  private final ConsentProgress consents;
  private final ObjectProvider<SpecialistSummaries> specialists;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Signups signups;

  MeController(
      Members members,
      AccountStore store,
      Catalogue catalogue,
      ConsentProgress consents,
      ObjectProvider<SpecialistSummaries> specialists,
      TransactionTemplate transactions,
      Clock clock,
      Signups signups) {
    this.members = members;
    this.store = store;
    this.catalogue = catalogue;
    this.consents = consents;
    this.specialists = specialists;
    this.transactions = transactions;
    this.clock = clock;
    this.signups = signups;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Me(
      UUID id,
      String role,
      String displayName,
      String phone,
      String appLanguage,
      Onboarding onboarding,
      String applicationStatus,
      List<Legal> legalToAccept) {}

  record Legal(
      @NotNull @Pattern(regexp = "terms|privacy-notice") String slug,
      @NotNull @Size(max = 20) String version) {}

  record Acceptance(
      @NotNull @Size(min = 1, max = 2) List<@jakarta.validation.Valid Legal> accepted) {}

  record Onboarding(boolean preferences, boolean privacy) {}

  record Preferences(
      @NotNull @Pattern(regexp = "en|fr") String appLanguage,
      @NotNull @Size(min = 1, max = 20) List<@NotNull String> sessionLanguages,
      @NotNull @Size(max = 50) List<@NotNull String> helpTopics,
      @NotNull @Pattern(regexp = "ANY|WOMAN|MAN") String specialistGender) {}

  @GetMapping("/v1/me")
  Me me(@AuthenticationPrincipal Jwt token) {
    Account account = members.signedIn(token);
    if (account.type() == Account.Type.SPECIALIST) {
      // Seeker sign-up steps do not apply; the application says where a specialist resumes.
      var summary = specialists.getObject().of(account.id());
      return new Me(
          account.id(),
          account.type().name(),
          summary.map(SpecialistSummaries.Summary::fullName).orElse(""),
          members.phone(account.id()).orElse(null),
          account.appLanguage().getLanguage(),
          new Onboarding(true, true),
          summary.map(SpecialistSummaries.Summary::applicationStatus).orElse(null),
          null);
    }
    var profile = store.seekerProfile(account.id());
    return new Me(
        account.id(),
        account.type().name(),
        profile.map(AccountStore.SeekerProfile::displayName).orElse(""),
        members.phone(account.id()).orElse(null),
        account.appLanguage().getLanguage(),
        new Onboarding(
            profile.map(p -> p.preferencesSavedAt() != null).orElse(true),
            consents.recordedAny(account.id())),
        null,
        legalToAccept(account.id()));
  }

  /** Legal texts published since this seeker accepted them (D44). */
  private List<Legal> legalToAccept(UUID accountId) {
    var accepted = store.legalVersions(accountId);
    var pending = new java.util.ArrayList<Legal>();
    if (accepted.isEmpty()) {
      return pending;
    }
    if (!signups.termsVersion().equals(accepted.get().terms())) {
      pending.add(new Legal("terms", signups.termsVersion()));
    }
    if (accepted.get().privacyNotice() != null
        && !signups.privacyNoticeVersion().equals(accepted.get().privacyNotice())) {
      pending.add(new Legal("privacy-notice", signups.privacyNoticeVersion()));
    }
    return pending;
  }

  @org.springframework.web.bind.annotation.PostMapping("/v1/me/legal-acceptance")
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.NO_CONTENT)
  void accept(
      @AuthenticationPrincipal Jwt token,
      @jakarta.validation.Valid @RequestBody Acceptance request) {
    Account account = members.require(token);
    for (Legal l : request.accepted()) {
      String current =
          l.slug().equals("terms") ? signups.termsVersion() : signups.privacyNoticeVersion();
      if (!current.equals(l.version())) {
        throw new care.solace.errors.SolaceException(IdentityError.OUTDATED_TERMS);
      }
      store.acceptLegal(account.id(), l.slug(), l.version());
    }
  }

  @GetMapping("/v1/me/preferences")
  Preferences preferences(@AuthenticationPrincipal Jwt token) {
    Account account = members.require(token);
    var profile =
        store
            .seekerProfile(account.id())
            .orElseThrow(() -> new SolaceException(IdentityError.NOT_A_MEMBER));
    return new Preferences(
        account.appLanguage().getLanguage(),
        profile.sessionLanguages(),
        profile.helpTopics(),
        profile.specialistGender());
  }

  @PutMapping("/v1/me/preferences")
  Preferences save(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Preferences body) {
    Account account = members.require(token);
    if (store.seekerProfile(account.id()).isEmpty()) {
      throw new SolaceException(IdentityError.NOT_A_MEMBER);
    }
    List<String> languages = body.sessionLanguages().stream().distinct().toList();
    List<String> topics = body.helpTopics().stream().distinct().toList();
    if (!catalogue.areSessionLanguages(languages) || !catalogue.areHelpTopics(topics)) {
      throw new SolaceException(IdentityError.UNKNOWN_CHOICE);
    }
    Locale appLanguage = Locale.of(body.appLanguage());
    transactions.executeWithoutResult(
        s ->
            store.savePreferences(
                account.id(),
                appLanguage,
                languages,
                topics,
                body.specialistGender(),
                clock.instant()));
    return preferences(token);
  }
}
