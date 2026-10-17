package care.solace.core.specialists;

import care.solace.audit.AuditLog;
import care.solace.core.catalog.Catalogue;
import care.solace.core.system.ObjectStorage;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff review of specialist applications (Admin Portal Spec, Specialist application workflow):
 * reviewers check the licence, the identity document, each language in the interview, and the
 * services and prices. Approval needs every item verified. A change request or rejection carries a
 * reason the applicant sees. Every action is audited; so is every opening of a document.
 */
@Service
class Reviews {

  /** The six items on the review screen. */
  static final int ITEMS = 6;

  private static final Set<String> BOOKABLE = Set.of("NATIVE", "FLUENT", "PROFESSIONAL");
  private static final List<ApplicationStatus> IN_REVIEW =
      List.of(ApplicationStatus.SUBMITTED, ApplicationStatus.UNDER_REVIEW);

  private final ApplicationStore applications;
  private final ReviewStore reviews;
  private final DocumentStore documents;
  private final ObjectStorage storage;
  private final Catalogue catalogue;
  private final SpecialistSettings settings;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Reviews(
      ApplicationStore applications,
      ReviewStore reviews,
      DocumentStore documents,
      ObjectStorage storage,
      Catalogue catalogue,
      SpecialistSettings settings,
      AuditLog audit,
      TransactionTemplate transactions,
      Clock clock) {
    this.applications = applications;
    this.reviews = reviews;
    this.documents = documents;
    this.storage = storage;
    this.catalogue = catalogue;
    this.settings = settings;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Everything the review screen and the specialist record show. */
  record Review(
      ApplicationStore.Profile profile,
      ReviewStore.State state,
      String professionLabel,
      Map<ReviewStore.Item, ReviewStore.Verification> items,
      List<ReviewStore.LanguageReview> languages,
      List<ApplicationStore.Offering> offerings,
      boolean withinBands,
      List<String> focusAreas,
      Optional<ApplicationStore.Payout> payout,
      List<DocumentStore.StoredFile> documents,
      List<ReviewStore.Activity> activity,
      int verifiedItems) {}

  record Queue(Map<ReviewStore.Tab, Integer> counts, List<QueueItem> items) {}

  record QueueItem(
      ReviewStore.QueueRow row,
      String professionLabel,
      List<ApplicationStore.Language> languages) {}

  /** A document's bytes, once the opening is audited. */
  record OpenedDocument(String fileName, String contentType, byte[] bytes) {}

  // --- Reading ---------------------------------------------------------------------------------

  Queue queue(ReviewStore.Tab tab, String profession, String language, Locale viewer) {
    Map<String, String> labels = professionLabels(viewer);
    var items =
        reviews.queue(tab, blankToNull(profession), blankToNull(language)).stream()
            .map(
                row ->
                    new QueueItem(
                        row,
                        row.profession() == null ? null : labels.get(row.profession()),
                        applications.languages(row.id())))
            .toList();
    return new Queue(reviews.counts(), items);
  }

  Review review(UUID id, Locale viewer) {
    ApplicationStore.Profile profile =
        applications.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    var items = reviews.items(id);
    var languages = reviews.languages(id);
    var offerings = applications.offerings(id);
    var pricing = settings.pricing();
    boolean withinBands =
        offerings.stream().anyMatch(ApplicationStore.Offering::enabled)
            && offerings.stream()
                .filter(ApplicationStore.Offering::enabled)
                .allMatch(
                    o ->
                        o.price() >= pricing.minPrice()
                            && o.price() <= pricing.maxPrice()
                            && pricing.durations().contains(o.durationMinutes()));
    boolean languagesVerified =
        !languages.isEmpty()
            && languages.stream().allMatch(l -> l.verifiedLevel() != null)
            && languages.stream().anyMatch(l -> BOOKABLE.contains(l.verifiedLevel()));
    int verified = items.size() + (languagesVerified ? 1 : 0) + (withinBands ? 1 : 0);
    return new Review(
        profile,
        reviews.state(id),
        profile.profession() == null ? null : professionLabels(viewer).get(profile.profession()),
        items,
        languages,
        offerings,
        withinBands,
        applications.focusAreas(id),
        applications.payout(id),
        documents.list(id),
        reviews.activity(id),
        verified);
  }

  List<UUID> search(String query) {
    String q = query.strip();
    if (q.length() < 2) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return reviews.search(q);
  }

  /** Audits the opening, then returns the file. Nothing is read if the entry cannot be written. */
  OpenedDocument open(UUID id, UUID fileId) {
    var file =
        documents.list(id).stream()
            .filter(f -> f.id().equals(fileId))
            .findFirst()
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    audit.read(
        "specialists.document.read", "specialist.document", fileId.toString(), id.toString());
    try (InputStream in = storage.get(settings.documents().bucket(), file.objectKey())) {
      return new OpenedDocument(file.fileName(), file.contentType(), in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- Changing --------------------------------------------------------------------------------

  void openForReview(UUID id, ReviewStore.StaffRef staff) {
    requireStatus(id, IN_REVIEW);
    change(
        id,
        "specialists.application.open",
        Map.of(),
        now -> {
          if (!reviews.open(id, staff, now)) {
            throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
          }
          reviews.record(id, "OPENED", null, null, staff, now);
        });
  }

  void verifyItem(
      UUID id,
      ReviewStore.Item item,
      boolean verified,
      LocalDate licenceValidUntil,
      ReviewStore.StaffRef staff) {
    ApplicationStore.Profile profile = underReview(id);
    if (verified) {
      switch (item) {
        case IDENTITY -> {
          if (documents.list(id).stream().noneMatch(f -> f.kind().equals("IDENTITY"))) {
            throw new SolaceException(SpecialistError.ITEM_NOT_READY);
          }
        }
        case LICENCE -> {
          if (licenceValidUntil == null || licenceValidUntil.isBefore(LocalDate.now(clock))) {
            throw new SolaceException(CommonError.REQUEST_INVALID);
          }
        }
        case PROFILE -> {
          if (isBlank(profile.bioEn()) || isBlank(profile.bioFr())) {
            throw new SolaceException(SpecialistError.ITEM_NOT_READY);
          }
        }
        case PAYOUT -> {
          if (applications.payout(id).isEmpty()) {
            throw new SolaceException(SpecialistError.ITEM_NOT_READY);
          }
        }
      }
    }
    change(
        id,
        verified ? "specialists.application.verify" : "specialists.application.unverify",
        Map.of("item", item.name()),
        now -> {
          if (verified) {
            reviews.verify(id, item, staff, now);
            if (item == ReviewStore.Item.LICENCE) {
              reviews.licenceValidUntil(id, licenceValidUntil);
            }
          } else {
            reviews.unverify(id, item);
          }
          reviews.record(
              id, verified ? "ITEM_VERIFIED" : "ITEM_UNVERIFIED", item.name(), null, staff, now);
        });
  }

  void verifyLanguage(UUID id, String code, String level, ReviewStore.StaffRef staff) {
    underReview(id);
    change(
        id,
        "specialists.application.verify-language",
        Map.of("language", code, "level", level),
        now -> {
          if (!reviews.verifyLanguage(id, code, level, staff, now)) {
            throw new SolaceException(CommonError.NOT_FOUND);
          }
          reviews.record(id, "LANGUAGE_VERIFIED", code, level, staff, now);
        });
  }

  /**
   * The reviewer writes the missing language; any change to the text needs the profile verified
   * again.
   */
  void bio(UUID id, String language, String text, ReviewStore.StaffRef staff) {
    underReview(id);
    String bio = text.strip();
    if (bio.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    change(
        id,
        "specialists.application.bio",
        Map.of("language", language),
        now -> {
          reviews.bio(id, language, bio, now);
          reviews.unverify(id, ReviewStore.Item.PROFILE);
          reviews.record(id, "BIO_ADDED", language, null, staff, now);
        });
  }

  void notes(UUID id, String notes) {
    exists(id);
    change(id, "specialists.application.notes", Map.of(), now -> reviews.notes(id, notes.strip()));
  }

  void interview(UUID id, Instant at, ReviewStore.StaffRef staff) {
    ApplicationStore.Profile profile = profile(id);
    if (!IN_REVIEW.contains(profile.status())) {
      throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
    }
    change(
        id,
        "specialists.application.interview",
        Map.of(),
        now -> {
          reviews.interview(id, at);
          reviews.record(id, "INTERVIEW_BOOKED", null, null, staff, now);
        });
  }

  void requestChanges(UUID id, String reason, ReviewStore.StaffRef staff) {
    String text = reason(reason);
    requireStatus(id, IN_REVIEW);
    change(
        id,
        "specialists.application.request-changes",
        Map.of(),
        now -> {
          if (!reviews.move(id, IN_REVIEW, ApplicationStatus.DRAFT, text, now)) {
            throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
          }
          reviews.record(id, "CHANGES_REQUESTED", null, null, staff, now);
        });
  }

  void approve(UUID id, ReviewStore.StaffRef staff, Locale viewer) {
    if (profile(id).status() != ApplicationStatus.UNDER_REVIEW) {
      throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
    }
    if (review(id, viewer).verifiedItems() < ITEMS) {
      throw new SolaceException(SpecialistError.REVIEW_INCOMPLETE);
    }
    change(
        id,
        "specialists.application.approve",
        Map.of(),
        now -> {
          if (!reviews.move(
              id, List.of(ApplicationStatus.UNDER_REVIEW), ApplicationStatus.APPROVED, null, now)) {
            throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
          }
          reviews.record(id, "APPROVED", null, null, staff, now);
        });
  }

  void reject(UUID id, String reason, ReviewStore.StaffRef staff) {
    String text = reason(reason);
    requireStatus(id, IN_REVIEW);
    change(
        id,
        "specialists.application.reject",
        Map.of(),
        now -> {
          if (!reviews.move(id, IN_REVIEW, ApplicationStatus.REJECTED, text, now)) {
            throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
          }
          reviews.record(id, "REJECTED", null, null, staff, now);
        });
  }

  void suspend(UUID id, String reason, ReviewStore.StaffRef staff) {
    String text = reason(reason);
    if (profile(id).status() != ApplicationStatus.APPROVED) {
      throw new SolaceException(SpecialistError.NOT_APPROVED);
    }
    change(
        id,
        "specialists.suspend",
        Map.of(),
        now -> {
          if (!reviews.move(
              id, List.of(ApplicationStatus.APPROVED), ApplicationStatus.SUSPENDED, text, now)) {
            throw new SolaceException(SpecialistError.NOT_APPROVED);
          }
          reviews.record(id, "SUSPENDED", null, null, staff, now);
        });
  }

  void reinstate(UUID id, ReviewStore.StaffRef staff) {
    if (profile(id).status() != ApplicationStatus.SUSPENDED) {
      throw new SolaceException(SpecialistError.NOT_APPROVED);
    }
    change(
        id,
        "specialists.reinstate",
        Map.of(),
        now -> {
          if (!reviews.move(
              id, List.of(ApplicationStatus.SUSPENDED), ApplicationStatus.APPROVED, null, now)) {
            throw new SolaceException(SpecialistError.NOT_APPROVED);
          }
          reviews.record(id, "REINSTATED", null, null, staff, now);
        });
  }

  // --- Helpers ---------------------------------------------------------------------------------

  /**
   * Audits the action, then makes the change in one transaction. Callers check the state first, so
   * an audited action is one that was allowed; the change itself still guards against a race.
   */
  private void change(
      UUID id,
      String action,
      Map<String, String> details,
      java.util.function.Consumer<Instant> work) {
    exists(id);
    audit.adminAction(action, "specialist.application", id.toString(), details);
    Instant now = clock.instant();
    transactions.executeWithoutResult(s -> work.accept(now));
  }

  private ApplicationStore.Profile profile(UUID id) {
    return applications.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private void requireStatus(UUID id, List<ApplicationStatus> allowed) {
    if (!allowed.contains(profile(id).status())) {
      throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
    }
  }

  private ApplicationStore.Profile underReview(UUID id) {
    ApplicationStore.Profile profile = profile(id);
    if (profile.status() != ApplicationStatus.UNDER_REVIEW) {
      throw new SolaceException(SpecialistError.NOT_UNDER_REVIEW);
    }
    return profile;
  }

  private void exists(UUID id) {
    reviews.exists(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private Map<String, String> professionLabels(Locale viewer) {
    return catalogue.allProfessions(viewer).stream()
        .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
  }

  private static String reason(String reason) {
    String text = reason == null ? "" : reason.strip();
    if (text.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return text;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
