package care.solace.tracking.rules;

import care.solace.events.Events;
import care.solace.tracking.PersonalData;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.episodes.EpisodeReconciler;
import care.solace.tracking.modules.ModuleStore;
import care.solace.tracking.sync.Sync;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pattern check (Tracking Service Spec, How the pattern check runs; D51). After every push or
 * delete, in the same transaction and right after the episode reconciler; and nightly, so window
 * rules stay right as days fall out of the window. Thresholds come from rule rows only; notes are
 * never read. A rule states a fact; it never scores, labels or predicts.
 */
@Service
public class PatternCheck implements PersonalData {

  private static final Logger LOG = LoggerFactory.getLogger(PatternCheck.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Douala");

  /** Payload of tracking.recommendation.created v1: ids and codes only, never health data. */
  public record RecommendationCreated(
      UUID recommendationId,
      String ruleCode,
      String moduleCode,
      String outcome,
      List<String> specialtyCodes) {}

  /** A recommendation was stored; published inside the transaction (D56). */
  public record Stored(UUID recommendationId) {}

  /** Payload of tracking.recommendation.resolved v1. */
  public record RecommendationResolved(UUID recommendationId, String ruleCode, String status) {}

  private final JdbcClient db;
  private final Sync sync;
  private final EpisodeReconciler episodes;
  private final ElementLibrary library;
  private final ModuleStore modules;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  PatternCheck(
      JdbcClient db,
      Sync sync,
      EpisodeReconciler episodes,
      ElementLibrary library,
      ModuleStore modules,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.db = db;
    this.sync = sync;
    this.episodes = episodes;
    this.library = library;
    this.modules = modules;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  @EventListener
  @Order(2)
  void on(Sync.EntriesChanged changed) {
    check(changed.userId(), changed.elementCodes(), true);
  }

  /** Nightly, 02:30 Douala time, for everyone enrolled; no withdrawals at night. */
  @Scheduled(cron = "${solace.tracking.pattern-check-cron:0 30 2 * * *}", zone = "Africa/Douala")
  void nightly() {
    for (UUID user :
        db.sql("SELECT DISTINCT user_id FROM enrolment WHERE left_at IS NULL")
            .query(UUID.class)
            .list()) {
      try {
        transactions.executeWithoutResult(s -> check(user, null, false));
      } catch (RuntimeException e) {
        LOG.warn("Nightly pattern check failed for one person", e);
      }
    }
  }

  /**
   * @param codes the elements that changed; null for all
   * @param withdraw edits and deletes withdraw recommendations that no longer hold
   */
  public void check(UUID user, Set<String> codes, boolean withdraw) {
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock.withZone(zone(user)));
    var profile = profile(user);
    for (var enrolment : enrolments(user)) {
      var version = modules.version(enrolment.versionId()).orElse(null);
      if (version == null || !version.status().equals("PUBLISHED")) {
        continue;
      }
      Map<String, ElementLibrary.Element> bound = new LinkedHashMap<>();
      for (var b : modules.bindings(version.id())) {
        library.byId(b.elementId()).ifPresent(e -> bound.put(e.code(), e));
      }
      if (codes != null && codes.stream().noneMatch(bound::containsKey)) {
        continue;
      }
      var signals = signals(user, bound.keySet(), today);
      for (var rule : modules.rules(version.id())) {
        evaluate(user, enrolment, version, rule, signals, profile, withdraw, now);
      }
    }
  }

  private void evaluate(
      UUID user,
      Enrolment enrolment,
      ModuleStore.Version version,
      ModuleStore.Rule rule,
      RuleEngine.Signals signals,
      Map<String, Object> profile,
      boolean withdraw,
      Instant now) {
    var result = RuleEngine.evaluate(rule.conditions(), signals);
    var open = openFor(user, rule.code());
    if (!result.held()) {
      if (withdraw && open.isPresent()) {
        db.sql("UPDATE recommendation SET status = 'SUPERSEDED', resolved_at = :now WHERE id = :id")
            .param("id", open.get())
            .param("now", Timestamp.from(now))
            .update();
        log(user, rule, version, result, "WITHDRAWN", open.get(), now);
        events.publishEvent(
            Events.envelope(
                "tracking.recommendation.resolved",
                1,
                user.toString(),
                null,
                new RecommendationResolved(open.get(), rule.code(), "SUPERSEDED")));
      }
      return;
    }
    if (blockedByUnless(rule.unless(), profile, enrolment.answer())) {
      log(user, rule, version, result, "BLOCKED_UNLESS", null, now);
      return;
    }
    boolean cooling =
        db.sql(
                "SELECT EXISTS (SELECT 1 FROM recommendation WHERE user_id = :u AND rule_code = :c AND next_eligible_at > :now)")
            .param("u", user)
            .param("c", rule.code())
            .param("now", Timestamp.from(now))
            .query(Boolean.class)
            .single();
    if (cooling) {
      log(user, rule, version, result, "BLOCKED_COOLDOWN", open.orElse(null), now);
      return;
    }
    if (open.isPresent()) {
      log(user, rule, version, result, "BLOCKED_OPEN", open.get(), now);
      return;
    }
    String outcome = rule.outcome();
    if (outcome.equals("PATTERN")
        && rule.repeatWithinDays() != null
        && firedWithin(user, rule.code(), rule.repeatWithinDays(), now)) {
      outcome = "INVITE";
    }
    UUID id = UUID.randomUUID();
    Map<String, Object> evidence = new LinkedHashMap<>(result.evidence());
    evidence.put("ruleVersion", version.number());
    evidence.put("conditions", rule.conditions());
    db.sql(
            """
            INSERT INTO recommendation (id, user_id, rule_id, rule_code, module_id, evidence_json, outcome,
                                        specialty_codes, status, created_at, next_eligible_at)
            VALUES (:id, :u, :r, :c, :m, CAST(:e AS jsonb), :o, :s, 'NEW', :now, :next)
            """)
        .param("id", id)
        .param("u", user)
        .param("r", rule.id())
        .param("c", rule.code())
        .param("m", version.moduleId())
        .param("e", write(evidence))
        .param("o", outcome)
        .param("s", rule.specialtyCodes().toArray(String[]::new))
        .param("now", Timestamp.from(now))
        .param("next", Timestamp.from(now.plus(Duration.ofDays(rule.cooldownDays()))))
        .update();
    log(user, rule, version, result, "FIRED", id, now);
    // After commit, the AI service may word it plainly (D56).
    events.publishEvent(new Stored(id));
    if (outcome.equals("INVITE")) {
      String moduleCode =
          modules.module(version.moduleId()).map(ModuleStore.Module::code).orElse("");
      events.publishEvent(
          Events.envelope(
              "tracking.recommendation.created",
              1,
              user.toString(),
              null,
              new RecommendationCreated(
                  id, rule.code(), moduleCode, outcome, rule.specialtyCodes())));
    }
  }

  /** "Unless" can only switch a rule off (Tracking Service Spec, What a rule is made of). */
  public static boolean blockedByUnless(
      JsonNode unless, Map<String, Object> profile, String answer) {
    if (unless == null) {
      return false;
    }
    for (JsonNode u : unless) {
      String field = u.path("field").asText();
      Object value = field.equals("enrolment_answer") ? answer : profile.get(field);
      if (value == null) {
        continue;
      }
      if (u.has("includes")
          && value instanceof List<?> list
          && list.contains(u.path("includes").asText())) {
        return true;
      }
      if (u.has("equals")) {
        JsonNode eq = u.path("equals");
        if ((eq.isBoolean() && value instanceof Boolean b && b == eq.asBoolean())
            || (eq.isTextual() && eq.asText().equals(String.valueOf(value)))) {
          return true;
        }
      }
    }
    return false;
  }

  /** Daily values of the bound elements over the last [400] days, and the person's episodes. */
  RuleEngine.Signals signals(UUID user, Set<String> bound, LocalDate today) {
    Map<String, NavigableMap<LocalDate, Object>> daily = new HashMap<>();
    Set<String> wanted = new HashSet<>(bound);
    wanted.removeIf(c -> library.latest(c).map(e -> e.isNote()).orElse(true));
    for (var o : sync.live(user, wanted, today.minusDays(400))) {
      Object value =
          o.value().integer() != null
              ? o.value().integer()
              : o.value().bool() != null ? o.value().bool() : o.value().text();
      if (value != null) {
        daily.computeIfAbsent(o.elementCode(), k -> new TreeMap<>()).put(o.observedOn(), value);
      }
    }
    return new RuleEngine.Signals(today, daily, episodes.episodes(user));
  }

  record Enrolment(UUID moduleId, UUID versionId, String answer) {}

  private List<Enrolment> enrolments(UUID user) {
    return db.sql(
            "SELECT module_id, module_version_id, enrolment_answer FROM enrolment WHERE user_id = :u AND left_at IS NULL")
        .param("u", user)
        .query(
            (rs, n) ->
                new Enrolment(
                    rs.getObject("module_id", UUID.class),
                    rs.getObject("module_version_id", UUID.class),
                    rs.getString("enrolment_answer")))
        .list();
  }

  private Map<String, Object> profile(UUID user) {
    return db.sql("SELECT * FROM tracking_profile WHERE user_id = :u")
        .param("u", user)
        .query(
            (rs, n) -> {
              Map<String, Object> p = new HashMap<>();
              if (rs.getString("pcos_status") != null) {
                p.put("pcos_status", rs.getString("pcos_status"));
              }
              if (rs.getObject("trying_to_conceive") != null) {
                p.put("trying_to_conceive", rs.getBoolean("trying_to_conceive"));
              }
              p.put("treatments", List.of((String[]) rs.getArray("treatments").getArray()));
              return p;
            })
        .optional()
        .orElse(Map.of());
  }

  private ZoneId zone(UUID user) {
    try {
      return db.sql(
              "SELECT recorded_tz FROM observation WHERE user_id = :u ORDER BY received_at DESC LIMIT 1")
          .param("u", user)
          .query(String.class)
          .optional()
          .map(ZoneId::of)
          .orElse(DEFAULT_ZONE);
    } catch (DateTimeException e) {
      return DEFAULT_ZONE;
    }
  }

  private Optional<UUID> openFor(UUID user, String ruleCode) {
    return db.sql(
            "SELECT id FROM recommendation WHERE user_id = :u AND rule_code = :c AND status IN ('NEW', 'SEEN', 'SNOOZED')")
        .param("u", user)
        .param("c", ruleCode)
        .query(UUID.class)
        .optional();
  }

  private boolean firedWithin(UUID user, String ruleCode, int days, Instant now) {
    return db.sql(
            "SELECT EXISTS (SELECT 1 FROM recommendation WHERE user_id = :u AND rule_code = :c AND created_at > :since)")
        .param("u", user)
        .param("c", ruleCode)
        .param("since", Timestamp.from(now.minus(Duration.ofDays(days))))
        .query(Boolean.class)
        .single();
  }

  private void log(
      UUID user,
      ModuleStore.Rule rule,
      ModuleStore.Version version,
      RuleEngine.Result result,
      String outcome,
      UUID recommendation,
      Instant now) {
    db.sql(
            """
            INSERT INTO rule_evaluation (user_id, rule_id, module_version_id, signals_json, result, recommendation_id, evaluated_at)
            VALUES (:u, :r, :v, CAST(:s AS jsonb), :res, :rec, :now)
            """)
        .param("u", user)
        .param("r", rule.id())
        .param("v", version.id())
        .param("s", write(Map.of("conditions", result.conditions())))
        .param("res", outcome)
        .param("rec", recommendation)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void erase(UUID userId) {
    db.sql("SELECT set_config('solace.erasure', 'on', true)").query(String.class).single();
    db.sql("DELETE FROM rule_evaluation WHERE user_id = :u").param("u", userId).update();
    db.sql("DELETE FROM recommendation WHERE user_id = :u").param("u", userId).update();
    db.sql("DELETE FROM tracking_profile WHERE user_id = :u").param("u", userId).update();
    db.sql("DELETE FROM enrolment WHERE user_id = :u").param("u", userId).update();
  }
}
