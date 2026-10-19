package care.solace.tracking.rules;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import care.solace.tracking.Seekers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person's recommendations (tag recommendations; D51): cards on the Track tab in the app
 * language, from the rule's EN/FR template until AI wording arrives in Phase 3. The person books,
 * snoozes or dismisses; nothing is ever sent to anyone else.
 */
@RestController
class RecommendationsController {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Seekers seekers;
  private final JdbcClient db;
  private final ApplicationEventPublisher events;
  private final AuditLog audit;
  private final int snoozeDays;
  private final Clock clock;

  RecommendationsController(
      Seekers seekers,
      JdbcClient db,
      ApplicationEventPublisher events,
      AuditLog audit,
      @Value("${solace.tracking.snooze-days:7}") int snoozeDays,
      Clock clock) {
    this.seekers = seekers;
    this.db = db;
    this.events = events;
    this.audit = audit;
    this.snoozeDays = snoozeDays;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Recommendation(
      UUID id,
      String ruleCode,
      String moduleCode,
      String outcome,
      List<String> specialtyCodes,
      String status,
      String message,
      JsonNode evidence,
      Instant createdAt,
      Instant snoozedUntil,
      boolean dataChanged,
      boolean aiWorded) {}

  record Snooze(Integer days) {}

  @GetMapping("/v1/tracking/recommendations")
  List<Recommendation> list(@AuthenticationPrincipal Jwt token, Locale locale) {
    UUID user = seekers.require(token);
    boolean fr = "fr".equals(locale.getLanguage());
    var list =
        db.sql(
                """
                SELECT r.*, m.code AS module_code, ru.message_en, ru.message_fr
                  FROM recommendation r JOIN module m ON m.id = r.module_id JOIN rule ru ON ru.id = r.rule_id
                 WHERE r.user_id = :u
                   AND (r.status IN ('NEW', 'SEEN')
                        OR (r.status = 'SNOOZED' AND r.snoozed_until <= :now)
                        OR (r.status = 'SUPERSEDED' AND r.seen_at IS NOT NULL AND r.resolved_at > :recent))
                 ORDER BY r.created_at DESC
                """)
            .param("u", user)
            .param("now", Timestamp.from(clock.instant()))
            .param("recent", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
            .query(
                (rs, n) -> {
                  JsonNode evidence = read(rs.getString("evidence_json"));
                  Map<String, Object> values =
                      JSON.convertValue(evidence, new TypeReference<>() {});
                  Timestamp snoozed = rs.getTimestamp("snoozed_until");
                  // The AI's plain wording, when it was made and passed its guardrails (D56).
                  String worded = rs.getString(fr ? "wording_fr" : "wording_en");
                  return new Recommendation(
                      rs.getObject("id", UUID.class),
                      rs.getString("rule_code"),
                      rs.getString("module_code"),
                      rs.getString("outcome"),
                      Arrays.asList((String[]) rs.getArray("specialty_codes").getArray()),
                      rs.getString("status"),
                      worded != null
                          ? worded
                          : RuleRules.render(
                              fr ? rs.getString("message_fr") : rs.getString("message_en"), values),
                      evidence,
                      rs.getTimestamp("created_at").toInstant(),
                      snoozed == null ? null : snoozed.toInstant(),
                      rs.getString("status").equals("SUPERSEDED"),
                      worded != null);
                })
            .list();
    audit.read("tracking.recommendations.read", "recommendations", null, user.toString());
    return list;
  }

  @PostMapping("/v1/tracking/recommendations/{id}/seen")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void seen(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    UUID user = seekers.require(token);
    db.sql(
            """
            UPDATE recommendation SET status = CASE WHEN status = 'NEW' THEN 'SEEN' ELSE status END,
                   seen_at = coalesce(seen_at, :now)
             WHERE id = :id AND user_id = :u
            """)
        .param("id", id)
        .param("u", user)
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  /** "Remind me later": hidden for [7] days; the cooldown restarts (Spec, Cooldown). */
  @PostMapping("/v1/tracking/recommendations/{id}/snooze")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void snooze(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID id,
      @RequestBody(required = false) Snooze body) {
    UUID user = seekers.require(token);
    int days =
        body != null && body.days() != null ? Math.max(1, Math.min(body.days(), 90)) : snoozeDays;
    Instant now = clock.instant();
    int n =
        db.sql(
                """
                UPDATE recommendation r SET status = 'SNOOZED', snoozed_until = :until, seen_at = coalesce(seen_at, :now),
                       next_eligible_at = :now + make_interval(days => (SELECT cooldown_days FROM rule WHERE id = r.rule_id))
                 WHERE id = :id AND user_id = :u AND status IN ('NEW', 'SEEN', 'SNOOZED')
                """)
            .param("id", id)
            .param("u", user)
            .param("now", Timestamp.from(now))
            .param("until", Timestamp.from(now.plus(Duration.ofDays(days))))
            .update();
    if (n == 0) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  @PostMapping("/v1/tracking/recommendations/{id}/dismissal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void dismiss(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    UUID user = seekers.require(token);
    Instant now = clock.instant();
    var code =
        db.sql(
                """
                UPDATE recommendation r SET status = 'DISMISSED', resolved_at = :now, seen_at = coalesce(seen_at, :now),
                       next_eligible_at = :now + make_interval(days => (SELECT cooldown_days FROM rule WHERE id = r.rule_id))
                 WHERE id = :id AND user_id = :u AND status IN ('NEW', 'SEEN', 'SNOOZED')
                RETURNING rule_code
                """)
            .param("id", id)
            .param("u", user)
            .param("now", Timestamp.from(now))
            .query(String.class)
            .optional()
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    events.publishEvent(
        Events.envelope(
            "tracking.recommendation.resolved",
            1,
            user.toString(),
            null,
            new PatternCheck.RecommendationResolved(id, code, "DISMISSED")));
  }

  private static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return JSON.createObjectNode();
    }
  }
}
