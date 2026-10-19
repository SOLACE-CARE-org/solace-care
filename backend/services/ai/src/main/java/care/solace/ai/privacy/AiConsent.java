package care.solace.ai.privacy;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The seeker's AI_ASSIST and SPECIALIST_SHARING choices, from core.consent.changed (System Design,
 * Event catalog). AI features for a seeker run only with AI_ASSIST (D56).
 */
@Component
public class AiConsent {

  static final String CONSUMER = "ai.consent-changed";
  private static final Set<String> KEPT = Set.of("AI_ASSIST", "SPECIALIST_SHARING");

  /** Payload of core.consent.changed v1. */
  record ConsentChanged(String purpose, boolean granted, String version) {}

  private final JdbcClient db;
  private final IdempotentConsumer once;

  AiConsent(JdbcClient db, IdempotentConsumer once) {
    this.db = db;
    this.once = once;
  }

  @KafkaListener(topics = "core.consent")
  void on(EventEnvelope event) {
    if (!"core.consent.changed".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () -> {
          var change = event.payloadAs(ConsentChanged.class);
          if (KEPT.contains(change.purpose())) {
            record(UUID.fromString(event.subjectId()), change.purpose(), change.granted(), event);
          }
        });
  }

  void record(UUID subject, String purpose, boolean granted, EventEnvelope event) {
    db.sql(
            """
            INSERT INTO ai_consent (subject_id, purpose, granted, changed_at) VALUES (:s, :p, :g, :t)
            ON CONFLICT (subject_id, purpose) DO UPDATE
               SET granted = EXCLUDED.granted, changed_at = EXCLUDED.changed_at
             WHERE ai_consent.changed_at <= EXCLUDED.changed_at
            """)
        .param("s", subject)
        .param("p", purpose)
        .param("g", granted)
        .param("t", Timestamp.from(event.occurredAt()))
        .update();
  }

  public boolean granted(UUID subject, String purpose) {
    return db.sql("SELECT granted FROM ai_consent WHERE subject_id = :s AND purpose = :p")
        .param("s", subject)
        .param("p", purpose)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }
}
