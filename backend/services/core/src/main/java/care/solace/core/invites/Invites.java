package care.solace.core.invites;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Invites from Tracking (tracking.recommendation.created and resolved; D52). Core keeps only ids,
 * the rule code and the specialties: the evidence stays in Tracking.
 */
@Service
class Invites implements TrackingInvites, care.solace.core.identity.PersonalData {

  static final String CONSUMER = "core.tracking-invites";

  record Created(
      UUID recommendationId,
      String ruleCode,
      String moduleCode,
      String outcome,
      List<String> specialtyCodes) {}

  record Resolved(UUID recommendationId, String ruleCode, String status) {}

  record Invite(
      UUID recommendationId, UUID accountId, List<String> specialtyCodes, String status) {}

  private final JdbcClient db;
  private final IdempotentConsumer once;

  Invites(JdbcClient db, IdempotentConsumer once) {
    this.db = db;
    this.once = once;
  }

  @KafkaListener(topics = "tracking.recommendation")
  void on(EventEnvelope event) {
    once.handle(
        CONSUMER,
        event,
        () -> {
          switch (event.type()) {
            case "tracking.recommendation.created" -> created(event);
            case "tracking.recommendation.resolved" ->
                resolved(event.payloadAs(Resolved.class).recommendationId(), event);
            default -> {}
          }
        });
  }

  private void created(EventEnvelope event) {
    var c = event.payloadAs(Created.class);
    db.sql(
            """
            INSERT INTO tracking_invite (recommendation_id, account_id, rule_code, specialty_codes, status, created_at)
            VALUES (:r, :a, :code, :s, 'OPEN', :at) ON CONFLICT DO NOTHING
            """)
        .param("r", c.recommendationId())
        .param("a", UUID.fromString(event.subjectId()))
        .param("code", c.ruleCode())
        .param("s", c.specialtyCodes().toArray(String[]::new))
        .param("at", Timestamp.from(event.occurredAt()))
        .update();
  }

  private void resolved(UUID recommendationId, EventEnvelope event) {
    db.sql(
            "UPDATE tracking_invite SET status = 'RESOLVED', resolved_at = :at WHERE recommendation_id = :r")
        .param("r", recommendationId)
        .param("at", Timestamp.from(event.occurredAt()))
        .update();
  }

  Optional<Invite> find(UUID accountId, UUID recommendationId) {
    return db.sql("SELECT * FROM tracking_invite WHERE recommendation_id = :r AND account_id = :a")
        .param("r", recommendationId)
        .param("a", accountId)
        .query(
            (rs, n) ->
                new Invite(
                    rs.getObject("recommendation_id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    Arrays.asList((String[]) rs.getArray("specialty_codes").getArray()),
                    rs.getString("status")))
        .optional();
  }

  @Override
  public boolean openFor(UUID accountId, UUID recommendationId) {
    return find(accountId, recommendationId).map(i -> i.status().equals("OPEN")).orElse(false);
  }

  @Override
  public String section() {
    return "trackingInvites";
  }

  /** The invites, as ids and specialties; the evidence is in Tracking's own copy. */
  @Override
  public Object export(UUID accountId) {
    return db.sql(
            "SELECT recommendation_id, rule_code, specialty_codes, status, created_at FROM tracking_invite WHERE account_id = :a")
        .param("a", accountId)
        .query(
            (rs, n) ->
                java.util.Map.of(
                    "recommendationId", rs.getString("recommendation_id"),
                    "ruleCode", rs.getString("rule_code"),
                    "specialties",
                        Arrays.asList((String[]) rs.getArray("specialty_codes").getArray()),
                    "status", rs.getString("status"),
                    "createdAt", rs.getTimestamp("created_at").toInstant().toString()))
        .list();
  }

  /** Erasure: invites are about the person (D40). */
  @Override
  public void erase(UUID accountId) {
    db.sql("DELETE FROM tracking_invite WHERE account_id = :a").param("a", accountId).update();
  }
}
