package care.solace.core.specialists;

import care.solace.audit.AuditLog;
import care.solace.core.catalog.Catalogue;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff checks of each profession and specialty a specialist claims (D62).
 *
 * <p>In an application under review, checking one is an item of the review, and the application's
 * two-person approval (D29) approves what was checked. Once approved, a profession the specialist
 * adds needs two people, one who checks the licence and someone else who approves it; a specialty
 * needs one reviewer. Every action is audited, and a decision after approval sends the neutral
 * "Decision made" push.
 */
@Service
class PracticeReviews {

  private final ApplicationStore applications;
  private final PracticeStore store;
  private final ReviewStore reviews;
  private final Reviews review;
  private final Catalogue catalogue;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  PracticeReviews(
      ApplicationStore applications,
      PracticeStore store,
      ReviewStore reviews,
      Reviews review,
      Catalogue catalogue,
      AuditLog audit,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.applications = applications;
    this.store = store;
    this.reviews = reviews;
    this.review = review;
    this.catalogue = catalogue;
    this.audit = audit;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  record Addition(PracticeStore.Addition row, String professionLabel, String specialtyLabel) {}

  List<Addition> additions(Locale viewer) {
    Map<String, String> professions = labels(catalogue.allProfessions(viewer));
    Map<String, String> specialties = labels(catalogue.allSpecialties(viewer));
    return store.additions().stream()
        .map(
            a ->
                new Addition(
                    a,
                    professions.get(a.profession()),
                    a.specialty() == null ? null : specialties.get(a.specialty())))
        .toList();
  }

  // --- Professions -----------------------------------------------------------------------------

  void verifyProfession(
      UUID id,
      String profession,
      boolean verified,
      LocalDate validUntil,
      ReviewStore.StaffRef staff) {
    boolean inApplication = context(id);
    pending(profession(id, profession));
    if (verified && (validUntil == null || validUntil.isBefore(LocalDate.now(clock)))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    change(
        id,
        verified ? "specialists.profession.verify" : "specialists.profession.unverify",
        Map.of("profession", profession),
        now -> {
          if (verified) {
            store.verifyProfession(id, profession, staff, validUntil, now);
          } else {
            store.unverifyProfession(id, profession);
          }
          reviews.record(
              id,
              verified ? "PROFESSION_VERIFIED" : "PROFESSION_UNVERIFIED",
              profession,
              null,
              staff,
              now);
          if (inApplication) {
            review.withdrawOnChange(id, staff, now);
          }
        });
  }

  /** The second of two people for a profession added after approval (D29). */
  void approveProfession(UUID id, String profession, ReviewStore.StaffRef staff) {
    if (context(id)) {
      // In an application, the application's own approval does this.
      throw new SolaceException(SpecialistError.NOT_APPROVED);
    }
    var row = pending(profession(id, profession));
    boolean checkedSpecialty =
        store.specialties(id).stream()
            .anyMatch(
                s ->
                    s.profession().equals(profession)
                        && s.status() == PracticeStore.Status.PENDING
                        && s.verification() != null);
    if (row.verification() == null || row.validUntil() == null || !checkedSpecialty) {
      throw new SolaceException(SpecialistError.PROFESSION_NOT_READY);
    }
    if (row.verification().by() != null && row.verification().by().id().equals(staff.id())) {
      throw new SolaceException(SpecialistError.SAME_APPROVER);
    }
    change(
        id,
        "specialists.profession.approve",
        Map.of("profession", profession),
        now -> {
          if (!store.decideProfession(
              id, profession, PracticeStore.Status.APPROVED, staff, null, now)) {
            throw new SolaceException(SpecialistError.NOT_PENDING);
          }
          store.approveVerifiedSpecialties(id, profession, staff, now);
          reviews.record(id, "PROFESSION_APPROVED", profession, null, staff, now);
          decided(id);
        });
  }

  void rejectProfession(UUID id, String profession, String reason, ReviewStore.StaffRef staff) {
    String text = reason(reason);
    boolean inApplication = context(id);
    pending(profession(id, profession));
    if (inApplication
        && store.professions(id).stream()
            .noneMatch(p -> p.live() && !p.code().equals(profession))) {
      throw new SolaceException(SpecialistError.LAST_ONE);
    }
    change(
        id,
        "specialists.profession.reject",
        Map.of("profession", profession),
        now -> {
          if (!store.decideProfession(
              id, profession, PracticeStore.Status.REJECTED, staff, text, now)) {
            throw new SolaceException(SpecialistError.NOT_PENDING);
          }
          store.declinePendingSpecialties(id, profession, staff, text, now);
          reviews.record(id, "PROFESSION_REJECTED", profession, null, staff, now);
          if (inApplication) {
            review.withdrawOnChange(id, staff, now);
          } else {
            decided(id);
          }
        });
  }

  // --- Specialties -----------------------------------------------------------------------------

  /** Under an approved profession, checking a specialty approves it; otherwise it marks it. */
  void verifySpecialty(
      UUID id, String profession, String specialty, boolean verified, ReviewStore.StaffRef staff) {
    boolean inApplication = context(id);
    var parent = profession(id, profession);
    pending(specialty(id, profession, specialty));
    boolean approveNow = !inApplication && parent.status() == PracticeStore.Status.APPROVED;
    if (approveNow && !verified) {
      throw new SolaceException(SpecialistError.NOT_PENDING);
    }
    String subject = profession + "/" + specialty;
    change(
        id,
        approveNow
            ? "specialists.specialty.approve"
            : verified ? "specialists.specialty.verify" : "specialists.specialty.unverify",
        Map.of("profession", profession, "specialty", specialty),
        now -> {
          if (approveNow) {
            store.verifySpecialty(id, profession, specialty, staff, now);
            if (!store.decideSpecialty(
                id, profession, specialty, PracticeStore.Status.APPROVED, staff, null, now)) {
              throw new SolaceException(SpecialistError.NOT_PENDING);
            }
            reviews.record(id, "SPECIALTY_APPROVED", subject, null, staff, now);
            decided(id);
            return;
          }
          if (verified) {
            store.verifySpecialty(id, profession, specialty, staff, now);
          } else {
            store.unverifySpecialty(id, profession, specialty);
          }
          reviews.record(
              id,
              verified ? "SPECIALTY_VERIFIED" : "SPECIALTY_UNVERIFIED",
              subject,
              null,
              staff,
              now);
          if (inApplication) {
            review.withdrawOnChange(id, staff, now);
          }
        });
  }

  void declineSpecialty(
      UUID id, String profession, String specialty, String reason, ReviewStore.StaffRef staff) {
    String text = reason(reason);
    boolean inApplication = context(id);
    var parent = profession(id, profession);
    pending(specialty(id, profession, specialty));
    // A profession still waiting keeps one specialty to be checked for; reject it instead.
    if (parent.status() != PracticeStore.Status.APPROVED
        && store.specialties(id).stream()
            .noneMatch(
                s ->
                    s.profession().equals(profession) && s.live() && !s.code().equals(specialty))) {
      throw new SolaceException(SpecialistError.LAST_ONE);
    }
    change(
        id,
        "specialists.specialty.decline",
        Map.of("profession", profession, "specialty", specialty),
        now -> {
          if (!store.decideSpecialty(
              id, profession, specialty, PracticeStore.Status.DECLINED, staff, text, now)) {
            throw new SolaceException(SpecialistError.NOT_PENDING);
          }
          reviews.record(id, "SPECIALTY_DECLINED", profession + "/" + specialty, null, staff, now);
          if (inApplication) {
            review.withdrawOnChange(id, staff, now);
          } else {
            decided(id);
          }
        });
  }

  // --- Helpers ---------------------------------------------------------------------------------

  /**
   * True for an application under review, false for an approved specialist; anything else is not
   * open to these checks.
   */
  private boolean context(UUID id) {
    var profile =
        applications.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return switch (profile.status()) {
      case UNDER_REVIEW -> true;
      case APPROVED -> false;
      default -> throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
    };
  }

  private PracticeStore.Profession profession(UUID id, String profession) {
    return store.professions(id).stream()
        .filter(p -> p.code().equals(profession))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private PracticeStore.Specialty specialty(UUID id, String profession, String specialty) {
    return store.specialties(id).stream()
        .filter(s -> s.profession().equals(profession) && s.code().equals(specialty))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private static PracticeStore.Profession pending(PracticeStore.Profession p) {
    if (p.status() != PracticeStore.Status.PENDING) {
      throw new SolaceException(SpecialistError.NOT_PENDING);
    }
    return p;
  }

  private static PracticeStore.Specialty pending(PracticeStore.Specialty s) {
    if (s.status() != PracticeStore.Status.PENDING) {
      throw new SolaceException(SpecialistError.NOT_PENDING);
    }
    return s;
  }

  /** Audits the action, then makes the change in one transaction, as Reviews does. */
  private void change(UUID id, String action, Map<String, String> details, Consumer<Instant> work) {
    audit.adminAction(action, "specialist.application", id.toString(), details);
    Instant now = clock.instant();
    transactions.executeWithoutResult(s -> work.accept(now));
  }

  /** The neutral "Decision made" push; it never names a profession or a specialty (D62). */
  private void decided(UUID id) {
    events.publishEvent(new ApplicationUpdated(id, "PRACTICE_DECIDED", null));
  }

  private static String reason(String reason) {
    String text = reason == null ? "" : reason.strip();
    if (text.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return text;
  }

  private static Map<String, String> labels(List<Catalogue.Entry> entries) {
    return entries.stream()
        .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
  }
}
