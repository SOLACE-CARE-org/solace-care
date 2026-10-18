package care.solace.core.content;

import care.solace.core.admin.StaffAccess;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing, translating, checking and publishing content (D44). Nothing publishes until both EN and
 * FR are done (CLAUDE.md, Language); a crisis resource also needs a clinical reviewer's check after
 * every edit, by someone who did not edit it.
 */
@Service
class Contents implements LegalTexts {

  record ConfigChanged(String kind, String key, int configVersion) {}

  private final ContentStore store;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  Contents(ContentStore store, ApplicationEventPublisher events, Clock clock) {
    this.store = store;
    this.events = events;
    this.clock = clock;
  }

  static boolean needsClinicalCheck(String type) {
    return type.equals("CRISIS_RESOURCE");
  }

  /** The state a row shows: never published, published with edits waiting, or published. */
  static String status(ContentStore.Item item) {
    if (item.version() == 0) {
      return "DRAFT";
    }
    return item.edited() ? "EDITED" : "PUBLISHED";
  }

  static boolean canPublish(ContentStore.Item item) {
    return item.enDone()
        && item.frDone()
        && !item.draft().titleEn().isBlank()
        && !item.draft().titleFr().isBlank()
        && !item.draft().bodyEn().isBlank()
        && !item.draft().bodyFr().isBlank()
        && (item.clinical().equals("NOT_NEEDED") || item.clinical().equals("APPROVED"))
        && (item.version() == 0 || item.edited());
  }

  List<ContentStore.Item> all(String type) {
    return store.all(type);
  }

  ContentStore.Item item(UUID id) {
    return store.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  Optional<ContentStore.Version> published(UUID id) {
    return store.latest(id);
  }

  @Transactional
  ContentStore.Item create(String type, String slug, String country) {
    UUID id = UUID.randomUUID();
    String clinical = needsClinicalCheck(type) ? "NEEDED" : "NOT_NEEDED";
    if (!store.create(id, slug, type, country, clinical, clock.instant())) {
      throw new SolaceException(ContentError.SLUG_TAKEN);
    }
    return item(id);
  }

  /** A changed language is no longer done; a changed crisis resource needs its check again. */
  @Transactional
  ContentStore.Item save(UUID id, ContentStore.Text text, StaffAccess.Staff staff) {
    var item = item(id);
    var old = item.draft();
    boolean enChanged =
        !old.titleEn().equals(text.titleEn()) || !old.bodyEn().equals(text.bodyEn());
    boolean frChanged =
        !old.titleFr().equals(text.titleFr()) || !old.bodyFr().equals(text.bodyFr());
    boolean numbersChanged = !Objects.equals(old.numbers(), text.numbers());
    boolean changed = enChanged || frChanged || numbersChanged;
    String clinical = needsClinicalCheck(item.type()) && changed ? "NEEDED" : item.clinical();
    store.saveDraft(
        id,
        text,
        item.enDone() && !enChanged && !numbersChanged,
        item.frDone() && !frChanged && !numbersChanged,
        clinical,
        staff.id(),
        staff.name(),
        clock.instant());
    return item(id);
  }

  @Transactional
  ContentStore.Item markDone(UUID id, String language, boolean done) {
    var item = item(id);
    var text = item.draft();
    boolean empty =
        language.equals("fr")
            ? text.titleFr().isBlank() || text.bodyFr().isBlank()
            : text.titleEn().isBlank() || text.bodyEn().isBlank();
    if (done && empty) {
      throw new SolaceException(ContentError.LANGUAGE_EMPTY);
    }
    store.markDone(id, language, done, clock.instant());
    return item(id);
  }

  @Transactional
  ContentStore.Item requestCheck(UUID id, StaffAccess.Staff staff) {
    var item = item(id);
    if (!item.clinical().equals("NEEDED")) {
      throw new SolaceException(ContentError.WRONG_STATE);
    }
    store.clinical(id, "REQUESTED", staff.id(), staff.name(), clock.instant());
    return item(id);
  }

  /** A clinical reviewer who did not make the edit (the same two-person rule as modules). */
  @Transactional
  ContentStore.Item approveCheck(UUID id, StaffAccess.Staff staff) {
    var item = item(id);
    if (!canApprove(item, staff)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    if (!item.clinical().equals("REQUESTED") && !item.clinical().equals("NEEDED")) {
      throw new SolaceException(ContentError.WRONG_STATE);
    }
    store.clinical(id, "APPROVED", staff.id(), staff.name(), clock.instant());
    return item(id);
  }

  static boolean canApprove(ContentStore.Item item, StaffAccess.Staff staff) {
    return staff.roles().contains("CLINICAL_REVIEWER") && !staff.id().equals(item.editedBy());
  }

  @Transactional
  ContentStore.Item publish(UUID id, String reason, StaffAccess.Staff staff) {
    var item = item(id);
    if (!canPublish(item)) {
      throw new SolaceException(ContentError.NOT_READY);
    }
    Instant now = clock.instant();
    int version = store.publish(item, reason, staff.name(), now);
    // The apps cache crisis resources; Tracking and AI cache nothing here but hear of it alike.
    events.publishEvent(
        Events.platformEnvelope(
            "core.config.changed", 1, new ConfigChanged("CONTENT", item.slug(), version)));
    return item(id);
  }

  @Override
  public Optional<String> currentVersion(String slug) {
    return store.bySlug(slug).filter(i -> i.version() > 0).map(i -> "v" + i.version());
  }
}
