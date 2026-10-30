package care.solace.core.specialists;

import care.solace.core.admin.PlatformSettings;
import care.solace.core.catalog.Catalogue;
import care.solace.core.identity.Account;
import care.solace.core.identity.MemberSignups;
import care.solace.core.identity.SpecialistSummaries;
import care.solace.core.scheduling.Availability;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specialist sign-up and the application (SpecSignup to SpecPayout). Every step can be saved again
 * while the application is a DRAFT; once submitted it is locked until a reviewer asks for changes.
 */
@Service
class Applications implements SpecialistSummaries {

  /** SpecProfessional: "Areas you work with (up to 5)". A decision, not a setting. */
  static final int MAX_FOCUS_AREAS = 5;

  /** The setting's ceiling, checked before the setting itself (D61). */
  static final int MOST_FOCUS_AREAS = 20;

  private static final Pattern E164 = Pattern.compile("^[+][1-9][0-9]{7,14}$");
  private static final Set<String> MOBILE_MONEY = Set.of("MTN_MOMO", "ORANGE_MONEY");

  private final PlatformSettings platform;
  private final ApplicationStore store;
  private final PracticeStore practice;
  private final Practices practices;
  private final ReviewStore reviews;
  private final DocumentStore documentStore;
  private final Documents documents;
  private final Availability availability;
  private final MemberSignups signups;
  private final Catalogue catalogue;
  private final SpecialistSettings settings;
  private final LivePricing pricing;
  private final care.solace.core.content.LegalTexts legal;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Applications(
      PlatformSettings platform,
      ApplicationStore store,
      PracticeStore practice,
      Practices practices,
      ReviewStore reviews,
      DocumentStore documentStore,
      Documents documents,
      Availability availability,
      MemberSignups signups,
      Catalogue catalogue,
      SpecialistSettings settings,
      LivePricing pricing,
      care.solace.core.content.LegalTexts legal,
      TransactionTemplate transactions,
      Clock clock) {
    this.platform = platform;
    this.store = store;
    this.practice = practice;
    this.practices = practices;
    this.reviews = reviews;
    this.documentStore = documentStore;
    this.documents = documents;
    this.availability = availability;
    this.signups = signups;
    this.catalogue = catalogue;
    this.settings = settings;
    this.pricing = pricing;
    this.legal = legal;
    this.transactions = transactions;
    this.clock = clock;
  }

  record SignupRequest(
      String fullName,
      String phone,
      String email,
      String termsVersion,
      String codeOfConductVersion,
      Locale appLanguage) {}

  record Practice(
      List<PracticeStore.Draft> professions,
      int yearsInPractice,
      List<String> focusAreas,
      String bio) {}

  record Steps(
      boolean practice,
      boolean languages,
      boolean documents,
      boolean services,
      boolean hours,
      boolean payout) {

    boolean all() {
      return practice && languages && documents && services && hours && payout;
    }
  }

  /** Everything the application screen shows, for the signed-in specialist only. */
  record View(
      ApplicationStore.Profile profile,
      Steps steps,
      List<PracticeStore.Draft> drafts,
      Practices.View practice,
      List<String> focusAreas,
      List<ApplicationStore.Language> languages,
      List<DocumentStore.StoredFile> documents,
      List<ApplicationStore.Offering> offerings,
      Optional<ApplicationStore.Payout> payout,
      SpecialistSettings.Pricing pricing,
      Optional<ApplicationStore.DraftedBio> draftedBio) {}

  void startSignup(SignupRequest request) {
    // The published versions, else the configured ones (D44).
    if (!legal
            .currentVersion("specialist-terms")
            .orElse(settings.termsVersion())
            .equals(request.termsVersion())
        || !legal
            .currentVersion("code-of-conduct")
            .orElse(settings.codeOfConductVersion())
            .equals(request.codeOfConductVersion())) {
      throw new SolaceException(SpecialistError.OUTDATED_TERMS);
    }
    String name = request.fullName().strip();
    if (name.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    signups.startSpecialist(
        new MemberSignups.Specialist(
            request.phone(), request.email(), request.appLanguage(), request.termsVersion()),
        id -> store.upsertDraft(id, name, request.codeOfConductVersion()));
  }

  View view(Account account) {
    ApplicationStore.Profile profile = profile(account);
    UUID id = profile.accountId();
    var files = documentStore.list(id);
    var offerings = store.offerings(id);
    var languages = store.languages(id);
    var payout = store.payout(id);
    var drafts = drafts(id);
    Set<String> kinds =
        files.stream().map(DocumentStore.StoredFile::kind).collect(Collectors.toSet());
    Set<String> licensed =
        files.stream()
            .filter(f -> f.kind().equals("LICENCE"))
            .map(DocumentStore.StoredFile::profession)
            .collect(Collectors.toSet());
    var steps =
        new Steps(
            profile.practiceSavedAt() != null && !drafts.isEmpty(),
            profile.languagesSavedAt() != null && !languages.isEmpty(),
            kinds.containsAll(Documents.REQUIRED)
                && !drafts.isEmpty()
                && drafts.stream().allMatch(d -> licensed.contains(d.profession())),
            profile.servicesSavedAt() != null
                && offerings.stream().anyMatch(ApplicationStore.Offering::enabled),
            availability.hasWeeklyHours(id),
            payout.isPresent());
    return new View(
        profile,
        steps,
        drafts,
        practices.view(id, account.appLanguage()),
        store.focusAreas(id),
        languages,
        files,
        offerings,
        payout,
        pricing.current(),
        store.draftedBio(id));
  }

  /** The professions in the order given, the first being the main one (D62). */
  void savePractice(Account account, Practice practice) {
    UUID id = editable(account).accountId();
    List<String> topics = practice.focusAreas().stream().distinct().toList();
    if (topics.isEmpty()
        || topics.size() > platform.number("solace.specialists.max-focus-areas", MAX_FOCUS_AREAS)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (!catalogue.areHelpTopics(topics)) {
      throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
    }
    List<PracticeStore.Draft> professions =
        practice.professions().stream()
            .map(
                d ->
                    new PracticeStore.Draft(
                        d.profession(),
                        d.licenceNumber().strip(),
                        d.licensingBody().strip(),
                        d.specialties().stream().map(String::strip).distinct().toList()))
            .toList();
    practices.checkDraft(professions);
    String bio = practice.bio().strip();
    if (bio.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String bioLanguage = account.appLanguage().getLanguage().equals("fr") ? "fr" : "en";
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          store.savePractice(id, practice.yearsInPractice(), bio, bioLanguage, topics, now);
          this.practice.replaceDraft(id, professions, now);
        });
    // A profession taken off the list takes its licence with it.
    documents.keepLicencesOf(
        id, professions.stream().map(PracticeStore.Draft::profession).toList());
  }

  void saveLanguages(Account account, List<ApplicationStore.Language> languages) {
    UUID id = editable(account).accountId();
    Set<String> codes = new HashSet<>();
    for (var language : languages) {
      if (!codes.add(language.code())) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    if (codes.isEmpty() || !catalogue.areSessionLanguages(codes)) {
      throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
    }
    transactions.executeWithoutResult(s -> store.saveLanguages(id, languages, clock.instant()));
  }

  void saveServices(Account account, List<ApplicationStore.Offering> offerings) {
    UUID id = editable(account).accountId();
    var pricing = this.pricing.current();
    Set<String> modalities = new HashSet<>();
    for (var offering : offerings) {
      if (!modalities.add(offering.modality())) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      if (offering.enabled()
          && (!pricing.durations().contains(offering.durationMinutes())
              || offering.price() < pricing.minPrice()
              || offering.price() > pricing.maxPrice())) {
        throw new SolaceException(SpecialistError.PRICE_OUT_OF_RANGE);
      }
    }
    if (offerings.stream().noneMatch(ApplicationStore.Offering::enabled)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    transactions.executeWithoutResult(s -> store.saveOfferings(id, offerings, clock.instant()));
  }

  void savePayout(Account account, ApplicationStore.Payout payout) {
    UUID id = editable(account).accountId();
    String holder = payout.holderName().strip();
    if (holder.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String phone = null;
    if (MOBILE_MONEY.contains(payout.method())) {
      if (payout.phone() == null || !E164.matcher(payout.phone()).matches()) {
        throw new SolaceException(SpecialistError.PAYOUT_METHOD_UNAVAILABLE);
      }
      phone = payout.phone();
    } else if (!settings.bankTransferEnabled()) {
      throw new SolaceException(SpecialistError.PAYOUT_METHOD_UNAVAILABLE);
    }
    var saved = new ApplicationStore.Payout(payout.method(), phone, holder);
    transactions.executeWithoutResult(s -> store.savePayout(id, saved, clock.instant()));
  }

  /**
   * The specialist checks the bio Solace drafted in their other language (D30), as it is or with
   * corrections. Allowed in any status: the draft is written during review.
   */
  void confirmBio(Account account, String correctedText) {
    UUID id = profile(account).accountId();
    var draft =
        store.draftedBio(id).orElseThrow(() -> new SolaceException(SpecialistError.NO_BIO_DRAFT));
    String text = correctedText == null ? null : correctedText.strip();
    if (text != null && text.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    transactions.executeWithoutResult(
        s -> store.confirmBio(id, draft.language(), text, clock.instant()));
  }

  /** A LICENCE is for one of the draft's professions, the main one when none is named (D62). */
  void uploadDocuments(
      Account account, Documents.Kind kind, String profession, List<Documents.Upload> uploads) {
    UUID id = editable(account).accountId();
    String forProfession = null;
    if (kind == Documents.Kind.LICENCE) {
      var drafts = drafts(id);
      if (drafts.isEmpty()) {
        throw new SolaceException(SpecialistError.APPLICATION_INCOMPLETE);
      }
      forProfession = profession == null ? drafts.getFirst().profession() : profession;
      String wanted = forProfession;
      if (drafts.stream().noneMatch(d -> d.profession().equals(wanted))) {
        throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
      }
    } else if (profession != null) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    documents.replace(id, kind, forProfession, uploads);
  }

  /** The draft as the Practice step last saved it, main profession first. */
  private List<PracticeStore.Draft> drafts(UUID id) {
    var specialties = practice.specialties(id);
    return practice.professions(id).stream()
        .filter(p -> p.status() != PracticeStore.Status.WITHDRAWN)
        .map(
            p ->
                new PracticeStore.Draft(
                    p.code(),
                    p.licenceNumber(),
                    p.licensingBody(),
                    specialties.stream()
                        .filter(s -> s.profession().equals(p.code()))
                        .filter(s -> s.status() != PracticeStore.Status.WITHDRAWN)
                        .map(PracticeStore.Specialty::code)
                        .toList()))
        .toList();
  }

  void submit(Account account) {
    View view = view(account);
    if (view.profile().status() != ApplicationStatus.DRAFT) {
      throw new SolaceException(SpecialistError.APPLICATION_LOCKED);
    }
    if (!view.steps().all()) {
      throw new SolaceException(SpecialistError.APPLICATION_INCOMPLETE);
    }
    UUID id = view.profile().accountId();
    boolean resubmission = reviews.state(id).changesRequestedAt() != null;
    Boolean submitted =
        transactions.execute(
            s -> {
              Instant now = clock.instant();
              if (!store.submit(id, now)) {
                return false;
              }
              if (resubmission) {
                // The applicant may have changed anything: every item is checked again.
                reviews.restartReview(id);
                practice.restartReview(id);
              }
              reviews.record(id, "SUBMITTED", null, null, null, now);
              return true;
            });
    if (!Boolean.TRUE.equals(submitted)) {
      throw new SolaceException(SpecialistError.APPLICATION_LOCKED);
    }
  }

  @Override
  public Optional<Summary> of(UUID accountId) {
    return store.find(accountId).map(p -> new Summary(p.fullName(), p.status().name()));
  }

  private ApplicationStore.Profile profile(Account account) {
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(SpecialistError.NOT_A_SPECIALIST);
    }
    return store
        .find(account.id())
        .orElseThrow(() -> new SolaceException(SpecialistError.NOT_A_SPECIALIST));
  }

  private ApplicationStore.Profile editable(Account account) {
    ApplicationStore.Profile profile = profile(account);
    if (profile.status() != ApplicationStatus.DRAFT) {
      throw new SolaceException(SpecialistError.APPLICATION_LOCKED);
    }
    return profile;
  }
}
