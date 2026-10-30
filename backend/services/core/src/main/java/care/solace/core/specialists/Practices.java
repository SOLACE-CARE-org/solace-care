package care.solace.core.specialists;

import care.solace.core.admin.PlatformSettings;
import care.solace.core.catalog.Catalogue;
import care.solace.core.identity.Account;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A specialist's professions and specialties (D62), as they claim them: in the application's
 * Practice step, then from the Profile tab once approved. Additions wait for staff; withdrawals
 * take effect at once. Requests for a specialty the catalogue lacks go to staff to create or match.
 */
@Service
class Practices {

  /** D62's default; the setting can lower or raise it within its bounds. */
  static final int MAX_PROFESSIONS = 3;

  static final int MAX_OPEN_REQUESTS = 3;

  /** The settings' ceilings, checked before the settings themselves (D61). */
  static final int MOST_PROFESSIONS = 10;

  static final int MOST_SPECIALTIES = 20;

  private final PracticeStore store;
  private final ApplicationStore applications;
  private final ReviewStore reviews;
  private final Documents documents;
  private final Catalogue catalogue;
  private final PlatformSettings platform;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Practices(
      PracticeStore store,
      ApplicationStore applications,
      ReviewStore reviews,
      Documents documents,
      Catalogue catalogue,
      PlatformSettings platform,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.applications = applications;
    this.reviews = reviews;
    this.documents = documents;
    this.catalogue = catalogue;
    this.platform = platform;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** One profession as the specialist sees it, labelled in their language. */
  record MyProfession(PracticeStore.Profession row, String label, List<MySpecialty> specialties) {}

  record MySpecialty(PracticeStore.Specialty row, String label) {}

  record MyRequest(PracticeStore.Request row, String specialtyLabel) {}

  record View(List<MyProfession> professions, List<MyRequest> requests) {}

  View view(UUID accountId, Locale viewer) {
    Map<String, String> professions = labels(catalogue.allProfessions(viewer));
    Map<String, String> specialties = labels(catalogue.allSpecialties(viewer));
    var rows = store.specialties(accountId);
    return new View(
        store.professions(accountId).stream()
            .filter(p -> p.status() != PracticeStore.Status.WITHDRAWN)
            .map(
                p ->
                    new MyProfession(
                        p,
                        professions.get(p.code()),
                        rows.stream()
                            .filter(s -> s.profession().equals(p.code()))
                            .filter(s -> s.status() != PracticeStore.Status.WITHDRAWN)
                            .map(s -> new MySpecialty(s, specialties.get(s.code())))
                            .toList()))
            .toList(),
        store.requests(accountId).stream()
            .map(
                r ->
                    new MyRequest(r, r.specialty() == null ? null : specialties.get(r.specialty())))
            .toList());
  }

  // --- The application's Practice step ---------------------------------------------------------

  /**
   * Checks a draft's professions against the catalogue: known and enabled, no repeats, each with at
   * least one specialty it allows, and no more than the setting.
   */
  void checkDraft(List<PracticeStore.Draft> drafts) {
    Set<String> codes = new HashSet<>();
    for (var draft : drafts) {
      if (!codes.add(draft.profession())
          || draft.licenceNumber().isEmpty()
          || draft.licensingBody().isEmpty()
          || draft.specialties().isEmpty()) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    if (drafts.isEmpty() || drafts.size() > maxProfessions()) {
      throw new SolaceException(SpecialistError.TOO_MANY_PROFESSIONS);
    }
    for (var draft : drafts) {
      if (!catalogue.isProfession(draft.profession())
          || !catalogue.allowedSpecialties(draft.profession()).containsAll(draft.specialties())) {
        throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
      }
    }
  }

  // --- After approval: the Profile tab ---------------------------------------------------------

  void addProfession(
      Account account,
      String profession,
      String licenceNumber,
      String licensingBody,
      List<String> specialties,
      Documents.Upload licence) {
    UUID id = approved(account);
    String number = licenceNumber.strip();
    String body = licensingBody.strip();
    List<String> picked = specialties.stream().map(String::strip).distinct().toList();
    if (number.isEmpty()
        || body.isEmpty()
        || picked.isEmpty()
        || picked.size() > MOST_SPECIALTIES) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (!catalogue.isProfession(profession)
        || !catalogue.allowedSpecialties(profession).containsAll(picked)) {
      throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
    }
    var current = store.professions(id);
    if (current.stream()
        .anyMatch(
            p ->
                p.code().equals(profession)
                    && (p.status() == PracticeStore.Status.APPROVED
                        || p.status() == PracticeStore.Status.PENDING))) {
      throw new SolaceException(SpecialistError.ALREADY_CLAIMED);
    }
    if (current.stream().filter(PracticeStore.Profession::live).count() >= maxProfessions()) {
      throw new SolaceException(SpecialistError.TOO_MANY_PROFESSIONS);
    }
    documents.replace(id, Documents.Kind.LICENCE, profession, List.of(licence));
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          store.addProfession(id, profession, number, body, picked, now);
          reviews.record(id, "PROFESSION_ADDED", profession, null, null, now);
        });
  }

  void withdrawProfession(Account account, String profession) {
    UUID id = approved(account);
    var row = profession(id, profession);
    if (!row.live()) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    long otherApproved =
        store.professions(id).stream()
            .filter(p -> p.status() == PracticeStore.Status.APPROVED)
            .filter(p -> !p.code().equals(profession))
            .count();
    if (row.status() == PracticeStore.Status.APPROVED && otherApproved == 0) {
      throw new SolaceException(SpecialistError.LAST_APPROVED);
    }
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          store.withdrawProfession(id, profession, now);
          reviews.record(id, "PROFESSION_WITHDRAWN", profession, null, null, now);
        });
  }

  void addSpecialty(
      Account account,
      String profession,
      String specialty,
      String note,
      Documents.Upload certificate) {
    UUID id = approved(account);
    var row = profession(id, profession);
    String text = note == null ? "" : note.strip();
    if (text.isEmpty() || text.length() > 1000) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (row.status() != PracticeStore.Status.APPROVED
        && row.status() != PracticeStore.Status.PENDING) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    if (!catalogue.allowedSpecialties(profession).contains(specialty)) {
      throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
    }
    if (store.specialties(id).stream()
        .anyMatch(
            s ->
                s.profession().equals(profession)
                    && s.code().equals(specialty)
                    && (s.status() == PracticeStore.Status.APPROVED
                        || s.status() == PracticeStore.Status.PENDING))) {
      throw new SolaceException(SpecialistError.ALREADY_CLAIMED);
    }
    UUID certificateId = certificate == null ? null : documents.addCertificate(id, certificate);
    try {
      transactions.executeWithoutResult(
          s -> {
            Instant now = clock.instant();
            store.addSpecialty(id, profession, specialty, text, certificateId, now);
            reviews.record(id, "SPECIALTY_ADDED", profession + "/" + specialty, null, null, now);
          });
    } catch (RuntimeException e) {
      if (certificateId != null) {
        documents.discard(id, certificateId);
      }
      throw e;
    }
  }

  void withdrawSpecialty(Account account, String profession, String specialty) {
    UUID id = approved(account);
    var row = profession(id, profession);
    var all =
        store.specialties(id).stream().filter(s -> s.profession().equals(profession)).toList();
    var target =
        all.stream()
            .filter(s -> s.code().equals(specialty) && s.live())
            .findFirst()
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    boolean lastApproved =
        row.status() == PracticeStore.Status.APPROVED
            && target.status() == PracticeStore.Status.APPROVED
            && all.stream()
                .noneMatch(
                    s ->
                        !s.code().equals(specialty) && s.status() == PracticeStore.Status.APPROVED);
    if (lastApproved) {
      throw new SolaceException(SpecialistError.LAST_APPROVED);
    }
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          store.withdrawSpecialty(id, profession, specialty, now);
          reviews.record(id, "SPECIALTY_WITHDRAWN", profession + "/" + specialty, null, null, now);
        });
  }

  /** While the application is a draft, or once approved; under a profession they claim. */
  void requestSpecialty(
      Account account, String profession, String name, String note, Documents.Upload certificate) {
    var profile = profile(account);
    if (profile.status() != ApplicationStatus.DRAFT
        && profile.status() != ApplicationStatus.APPROVED) {
      throw new SolaceException(SpecialistError.APPLICATION_LOCKED);
    }
    UUID id = profile.accountId();
    if (!profession(id, profession).live()) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    String proposed = name == null ? "" : name.strip();
    String text = note == null ? "" : note.strip();
    if (proposed.isEmpty() || proposed.length() > 80 || text.isEmpty() || text.length() > 1000) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (store.openRequests(id)
        >= platform.number("solace.specialists.max-open-specialty-requests", MAX_OPEN_REQUESTS)) {
      throw new SolaceException(SpecialistError.TOO_MANY_REQUESTS);
    }
    UUID certificateId = certificate == null ? null : documents.addCertificate(id, certificate);
    try {
      transactions.executeWithoutResult(
          s -> {
            Instant now = clock.instant();
            store.insertRequest(
                new PracticeStore.Request(
                    UUID.randomUUID(),
                    id,
                    profession,
                    proposed,
                    text,
                    certificateId,
                    "OPEN",
                    null,
                    null,
                    null,
                    now));
            reviews.record(id, "SPECIALTY_REQUESTED", profession, null, null, now);
          });
    } catch (RuntimeException e) {
      if (certificateId != null) {
        documents.discard(id, certificateId);
      }
      throw e;
    }
  }

  void withdrawRequest(Account account, UUID requestId) {
    UUID id = profile(account).accountId();
    var request =
        store
            .request(requestId)
            .filter(r -> r.accountId().equals(id))
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (!store.decideRequest(requestId, "WITHDRAWN", null, null, null, now)) {
            throw new SolaceException(SpecialistError.REQUEST_CLOSED);
          }
          reviews.record(id, "REQUEST_WITHDRAWN", request.profession(), null, null, now);
        });
  }

  int maxProfessions() {
    return (int)
        Math.min(
            MOST_PROFESSIONS,
            platform.number("solace.specialists.max-professions", MAX_PROFESSIONS));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private PracticeStore.Profession profession(UUID id, String profession) {
    return store.professions(id).stream()
        .filter(p -> p.code().equals(profession))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private ApplicationStore.Profile profile(Account account) {
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(SpecialistError.NOT_A_SPECIALIST);
    }
    return applications
        .find(account.id())
        .orElseThrow(() -> new SolaceException(SpecialistError.NOT_A_SPECIALIST));
  }

  /** Additions and withdrawals are for approved specialists; a draft uses the Practice step. */
  private UUID approved(Account account) {
    var profile = profile(account);
    if (profile.status() != ApplicationStatus.APPROVED) {
      throw new SolaceException(SpecialistError.NOT_APPROVED);
    }
    return profile.accountId();
  }

  private static Map<String, String> labels(List<Catalogue.Entry> entries) {
    return entries.stream()
        .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
  }
}
