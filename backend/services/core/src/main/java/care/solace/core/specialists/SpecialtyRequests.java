package care.solace.core.specialists;

import care.solace.audit.AuditLog;
import care.solace.core.catalog.Catalogue;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff answers to requests for a specialty the catalogue lacks (D62). Creating one adds it under a
 * main specialty and links it to the request's profession, so every specialist with that profession
 * can choose it from then on. Matching answers with one that exists. Either way the specialty joins
 * the specialist's practice as PENDING, to be checked like any other.
 */
@Service
class SpecialtyRequests {

  private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]*$");

  private final PracticeStore store;
  private final ApplicationStore applications;
  private final ReviewStore reviews;
  private final Reviews review;
  private final Catalogue catalogue;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  SpecialtyRequests(
      PracticeStore store,
      ApplicationStore applications,
      ReviewStore reviews,
      Reviews review,
      Catalogue catalogue,
      AuditLog audit,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.applications = applications;
    this.reviews = reviews;
    this.review = review;
    this.catalogue = catalogue;
    this.audit = audit;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  record Listed(
      PracticeStore.Request row, String fullName, String professionLabel, String specialtyLabel) {}

  List<Listed> list(String status, Locale viewer) {
    Map<String, String> professions = labels(catalogue.allProfessions(viewer));
    Map<String, String> specialties = labels(catalogue.allSpecialties(viewer));
    return store.requestsByStatus(status).stream()
        .map(r -> listed(r, professions, specialties))
        .toList();
  }

  Listed one(UUID id, Locale viewer) {
    return listed(
        open(id, false),
        labels(catalogue.allProfessions(viewer)),
        labels(catalogue.allSpecialties(viewer)));
  }

  void create(
      UUID id,
      String code,
      String labelEn,
      String labelFr,
      String parentCode,
      String reason,
      ReviewStore.StaffRef staff) {
    var request = open(id, true);
    String en = labelEn.strip();
    String fr = labelFr.strip();
    if (!CODE.matcher(code).matches() || en.isEmpty() || fr.isEmpty() || reason.isBlank()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (catalogue.specialtyExists(code) || !catalogue.isMainSpecialty(parentCode)) {
      throw new SolaceException(SpecialistError.SPECIALTY_CONFLICT);
    }
    audit.adminAction(
        "catalogue.specialties.save",
        "specialty",
        code,
        reason.strip(),
        Map.of("enabled", "true", "parent", parentCode, "request", id.toString()));
    audit.adminAction(
        "specialty-requests.create", "specialty.request", id.toString(), Map.of("specialty", code));
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          catalogue.createSpecialty(code, en, fr, parentCode);
          catalogue.linkSpecialty(request.profession(), code);
          answer(request, "CREATED", code, staff, reason.strip(), now);
        });
  }

  void match(UUID id, String specialty, String reason, ReviewStore.StaffRef staff) {
    var request = open(id, true);
    if (reason.isBlank()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (catalogue.specialties(Locale.ENGLISH).stream().noneMatch(e -> e.code().equals(specialty))) {
      throw new SolaceException(SpecialistError.UNKNOWN_CHOICE);
    }
    audit.adminAction(
        "specialty-requests.match",
        "specialty.request",
        id.toString(),
        Map.of("specialty", specialty));
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (catalogue.linkSpecialty(request.profession(), specialty)) {
            audit.adminAction(
                "catalogue.professions.link",
                "profession",
                request.profession(),
                reason.strip(),
                Map.of("specialty", specialty, "request", id.toString()));
          }
          answer(request, "MATCHED", specialty, staff, reason.strip(), now);
        });
  }

  void decline(UUID id, String reason, ReviewStore.StaffRef staff) {
    var request = open(id, true);
    String text = reason == null ? "" : reason.strip();
    if (text.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    audit.adminAction("specialty-requests.decline", "specialty.request", id.toString(), Map.of());
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (!store.decideRequest(id, "DECLINED", null, staff, text, now)) {
            throw new SolaceException(SpecialistError.REQUEST_CLOSED);
          }
          reviews.record(
              request.accountId(), "REQUEST_DECLINED", request.profession(), null, staff, now);
          events.publishEvent(
              new ApplicationUpdated(request.accountId(), "PRACTICE_DECIDED", null));
        });
  }

  /**
   * Closes the request and puts the specialty in the specialist's practice to be checked, with the
   * request's note and certificate, unless they already claim it there.
   */
  private void answer(
      PracticeStore.Request request,
      String status,
      String specialty,
      ReviewStore.StaffRef staff,
      String reason,
      Instant now) {
    if (!store.decideRequest(request.id(), status, specialty, staff, reason, now)) {
      throw new SolaceException(SpecialistError.REQUEST_CLOSED);
    }
    UUID account = request.accountId();
    boolean claimed =
        store.specialties(account).stream()
            .anyMatch(
                s ->
                    s.profession().equals(request.profession())
                        && s.code().equals(specialty)
                        && (s.status() == PracticeStore.Status.APPROVED
                            || s.status() == PracticeStore.Status.PENDING));
    if (!claimed) {
      store.addSpecialty(
          account, request.profession(), specialty, request.note(), request.certificateId(), now);
    }
    reviews.record(
        account,
        status.equals("CREATED") ? "REQUEST_CREATED" : "REQUEST_MATCHED",
        request.profession() + "/" + specialty,
        null,
        staff,
        now);
    // An application under review has something new to check: a waiting approval no longer stands.
    if (applications
        .find(account)
        .map(p -> p.status() == ApplicationStatus.UNDER_REVIEW)
        .orElse(false)) {
      review.withdrawOnChange(account, staff, now);
    }
    events.publishEvent(new ApplicationUpdated(account, "PRACTICE_DECIDED", null));
  }

  private PracticeStore.Request open(UUID id, boolean mustBeOpen) {
    var request = store.request(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (mustBeOpen && !request.status().equals("OPEN")) {
      throw new SolaceException(SpecialistError.REQUEST_CLOSED);
    }
    return request;
  }

  private Listed listed(
      PracticeStore.Request r, Map<String, String> professions, Map<String, String> specialties) {
    return new Listed(
        r,
        applications.find(r.accountId()).map(ApplicationStore.Profile::fullName).orElse(""),
        professions.get(r.profession()),
        r.specialty() == null ? null : specialties.get(r.specialty()));
  }

  private static Map<String, String> labels(List<Catalogue.Entry> entries) {
    return entries.stream()
        .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
  }
}
