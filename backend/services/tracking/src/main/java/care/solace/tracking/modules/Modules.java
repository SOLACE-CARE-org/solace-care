package care.solace.tracking.modules;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import care.solace.tracking.TrackingError;
import care.solace.tracking.context.ContextQuestions;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.episodes.EpisodeModels;
import care.solace.tracking.rules.RuleRules;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The module builder's lifecycle (Tracking Service Spec, Versions and lifecycle; D51): Draft, In
 * review, Published, Retired. Only Operations or a Super admin drafts; only a Clinical reviewer who
 * did not draft the version approves it. A published version is frozen.
 */
@Service
public class Modules {

  /**
   * The accents a card may use, as design-system colour tokens (ModuleDetails.dc.html: green, rose,
   * amber; blue for later modules), so every card passes contrast checks.
   */
  public static final Set<String> ACCENTS =
      Set.of("brand", "track-pink", "track-gold", "track-blue");

  public static final int ELEMENT_WARNING = 6;

  /** What stops a version from being submitted; empty when it is ready. */
  public record Readiness(List<String> problems, List<String> warnings) {

    public boolean ready() {
      return problems.isEmpty();
    }
  }

  public record ModulePublished(String moduleCode, String version) {}

  private final ModuleStore store;
  private final ElementLibrary library;
  private final ApplicationEventPublisher events;
  private final EpisodeModels models;
  private final ContextQuestions questions;
  private final Clock clock;

  Modules(
      ModuleStore store,
      ElementLibrary library,
      EpisodeModels models,
      ContextQuestions questions,
      ApplicationEventPublisher events,
      Clock clock) {
    this.store = store;
    this.library = library;
    this.models = models;
    this.questions = questions;
    this.events = events;
    this.clock = clock;
  }

  static void requireDrafter(StaffGate.Staff staff) {
    if (!staff.roles().contains("OPERATIONS") && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
  }

  @Transactional
  public ModuleStore.Version create(String code, String accent, StaffGate.Staff staff) {
    requireDrafter(staff);
    if (!ACCENTS.contains(accent)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    UUID moduleId = UUID.randomUUID();
    Instant now = clock.instant();
    if (!store.createModule(moduleId, code, accent, staff.id(), now)) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    UUID versionId = UUID.randomUUID();
    store.insertVersion(versionId, moduleId, 1, 0, empty(), staff.id(), staff.name(), now);
    return store.version(versionId).orElseThrow();
  }

  /** A new draft from the published (or latest) version; numbered when its changes are known. */
  @Transactional
  public ModuleStore.Version newVersion(UUID moduleId, StaffGate.Staff staff) {
    requireDrafter(staff);
    var versions = store.versions(moduleId);
    if (versions.isEmpty()) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    if (versions.stream()
        .anyMatch(v -> v.status().equals("DRAFT") || v.status().equals("IN_REVIEW"))) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    var base = store.published(moduleId).orElse(versions.get(0));
    UUID id = UUID.randomUUID();
    store.insertVersion(
        id,
        moduleId,
        base.major(),
        base.minor() + 1,
        base.details(),
        staff.id(),
        staff.name(),
        clock.instant());
    store.replaceBindings(id, store.bindings(base.id()));
    store.replaceQuestions(id, store.questions(base.id()));
    for (var r : store.rules(base.id())) {
      store.saveRule(
          new ModuleStore.Rule(
              UUID.randomUUID(),
              id,
              r.code(),
              r.conditions(),
              r.unless(),
              r.outcome(),
              r.specialtyCodes(),
              r.messageEn(),
              r.messageFr(),
              r.cooldownDays(),
              r.repeatWithinDays(),
              r.position()));
    }
    return store.version(id).orElseThrow();
  }

  public ModuleStore.Version draft(UUID versionId) {
    var v = store.version(versionId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!v.status().equals("DRAFT")) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    return v;
  }

  @Transactional
  public void saveDetails(
      UUID versionId, ModuleStore.Details details, String accent, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(versionId);
    if (details.minAge() != null && details.minAge() < 18) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (accent != null) {
      if (!ACCENTS.contains(accent)) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      store.setColour(v.moduleId(), accent);
    }
    store.saveDetails(versionId, details);
  }

  /** Bindings, then the version number: a changed set of required elements is a major version. */
  @Transactional
  public void saveBindings(
      UUID versionId, List<ModuleStore.Binding> bindings, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(versionId);
    for (var b : bindings) {
      library
          .byId(b.elementId())
          .orElseThrow(() -> new SolaceException(CommonError.REQUEST_INVALID));
    }
    store.replaceBindings(versionId, bindings);
    renumber(v);
  }

  /** At most 3 published context questions, asked once on enrolment (D61). */
  public static final int MAX_QUESTIONS = 3;

  @Transactional
  public void saveQuestions(UUID versionId, List<String> codes, StaffGate.Staff staff) {
    requireDrafter(staff);
    draft(versionId);
    if (codes.size() > MAX_QUESTIONS
        || codes.stream().distinct().count() != codes.size()
        || codes.stream().anyMatch(c -> questions.find(c).isEmpty())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    store.replaceQuestions(versionId, codes);
  }

  private void renumber(ModuleStore.Version v) {
    var published = store.published(v.moduleId());
    if (published.isEmpty()) {
      return;
    }
    Set<UUID> before = required(published.get().id());
    Set<UUID> now = required(v.id());
    int major = before.equals(now) ? published.get().major() : published.get().major() + 1;
    int minor = before.equals(now) ? published.get().minor() + 1 : 0;
    if (major != v.major() || minor != v.minor()) {
      store.renumber(v.id(), major, minor);
    }
  }

  private Set<UUID> required(UUID versionId) {
    return store.bindings(versionId).stream()
        .filter(ModuleStore.Binding::required)
        .map(ModuleStore.Binding::elementId)
        .collect(Collectors.toSet());
  }

  @Transactional
  public ModuleStore.Rule saveRule(ModuleStore.Rule rule, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(rule.moduleVersionId());
    var problems =
        RuleRules.problems(
            rule.conditions(), rule.unless(), bound(v.id()), models.all(), questions.all());
    if (!problems.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (!RuleRules.wordingAllowed(rule.messageEn())
        || !RuleRules.wordingAllowed(rule.messageFr())) {
      throw new SolaceException(TrackingError.BLOCKED_WORDING);
    }
    boolean invites = rule.outcome().equals("INVITE") || rule.repeatWithinDays() != null;
    if (invites && rule.specialtyCodes().isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    store.saveRule(rule);
    return store.rule(rule.id()).orElseThrow();
  }

  @Transactional
  public void deleteRule(UUID ruleId, StaffGate.Staff staff) {
    requireDrafter(staff);
    var rule = store.rule(ruleId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    draft(rule.moduleVersionId());
    store.deleteRule(ruleId);
  }

  /** The elements a version binds, by code. */
  public Map<String, ElementLibrary.Element> bound(UUID versionId) {
    Map<String, ElementLibrary.Element> out = new LinkedHashMap<>();
    for (var b : store.bindings(versionId)) {
      library.byId(b.elementId()).ifPresent(e -> out.put(e.code(), e));
    }
    return out;
  }

  public Readiness readiness(ModuleStore.Version v) {
    List<String> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    var d = v.details();
    for (var field :
        List.of(
            Map.entry("name", d.nameEn() + "|" + d.nameFr()),
            Map.entry("description", d.descriptionEn() + "|" + d.descriptionFr()),
            Map.entry("audience", d.audienceEn() + "|" + d.audienceFr()),
            Map.entry("imageAlt", d.imageAltEn() + "|" + d.imageAltFr()))) {
      String[] both = field.getValue().split("\\|", -1);
      if (both[0].isBlank() || both[1].isBlank()) {
        problems.add(field.getKey());
      }
    }
    if (d.imageAssetId() == null) {
      problems.add("image");
    }
    int elements = store.bindings(v.id()).size();
    if (elements == 0) {
      problems.add("elements");
    } else if (elements > ELEMENT_WARNING) {
      warnings.add("elements");
    }
    if (store.bindings(v.id()).stream().noneMatch(ModuleStore.Binding::required)) {
      problems.add("requiredElement");
    }
    var bound = bound(v.id());
    for (var r : store.rules(v.id())) {
      if (!RuleRules.problems(r.conditions(), r.unless(), bound, models.all(), questions.all())
              .isEmpty()
          || r.messageEn().isBlank()
          || r.messageFr().isBlank()) {
        problems.add("rule:" + r.code());
      }
    }
    return new Readiness(problems, warnings);
  }

  @Transactional
  public void submit(UUID versionId, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(versionId);
    if (!readiness(v).ready()) {
      throw new SolaceException(TrackingError.NOT_READY);
    }
    store.move(versionId, "DRAFT", "IN_REVIEW", null, staff.id(), staff.name(), clock.instant());
  }

  /** A Clinical reviewer approves, or returns the version to draft with comments. */
  @Transactional
  public void review(UUID versionId, boolean approve, String comments, StaffGate.Staff staff) {
    if (!staff.clinicalReviewer()) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    var v = store.version(versionId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() != null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    if (staff.id().equals(v.draftedBy())) {
      throw new SolaceException(TrackingError.SAME_PERSON);
    }
    Instant now = clock.instant();
    store.review(
        versionId, staff.id(), staff.name(), approve ? "APPROVED" : "RETURNED", comments, now);
    if (approve) {
      store.approve(versionId, staff.id(), staff.name(), now);
    } else {
      store.move(versionId, "IN_REVIEW", "DRAFT", null, null, null, now);
    }
  }

  /** Publishes an approved version; the old one retires and enrolled people move to the new one. */
  @Transactional
  public void publish(UUID versionId, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = store.version(versionId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() == null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    Instant now = clock.instant();
    store
        .published(v.moduleId())
        .ifPresent(old -> store.move(old.id(), "PUBLISHED", "RETIRED", null, null, null, now));
    store.move(versionId, "IN_REVIEW", "PUBLISHED", null, null, null, now);
    store.moveEnrolments(v.moduleId(), versionId);
    var module = store.module(v.moduleId()).orElseThrow();
    events.publishEvent(
        Events.platformEnvelope(
            "tracking.module.published", 1, new ModulePublished(module.code(), v.number())));
  }

  @Transactional
  public void retire(UUID versionId, StaffGate.Staff staff) {
    requireDrafter(staff);
    if (!store.move(versionId, "PUBLISHED", "RETIRED", null, null, null, clock.instant())) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
  }

  private static ModuleStore.Details empty() {
    return new ModuleStore.Details("", "", "", "", null, "", "", null, "", "", null, null);
  }
}
