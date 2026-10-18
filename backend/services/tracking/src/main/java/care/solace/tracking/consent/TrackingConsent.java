package care.solace.tracking.consent;

import care.solace.errors.SolaceException;
import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import care.solace.tracking.TrackingError;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The person's TRACKING consent, as Core publishes it (core.consent.changed; System Design,
 * Consent). Tracking processes nothing new for someone who has not said yes (D49).
 */
@Component
public class TrackingConsent implements care.solace.tracking.PersonalData {

  static final String CONSUMER = "tracking.consent-changed";

  /** Payload of core.consent.changed v1. */
  record ConsentChanged(String purpose, boolean granted, String version) {}

  private final JdbcClient db;
  private final IdempotentConsumer once;

  TrackingConsent(JdbcClient db, IdempotentConsumer once) {
    this.db = db;
    this.once = once;
  }

  @KafkaListener(topics = "core.consent")
  void on(EventEnvelope event) {
    if (!"core.consent.changed".equals(event.type())) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () -> {
          var change = event.payloadAs(ConsentChanged.class);
          if ("TRACKING".equals(change.purpose())) {
            record(UUID.fromString(event.subjectId()), change.granted(), change.version(), event);
          }
        });
  }

  private void record(UUID user, boolean granted, String version, EventEnvelope event) {
    db.sql(
            """
            INSERT INTO tracking_consent (user_id, granted, version, changed_at)
            VALUES (:u, :g, :v, :t)
            ON CONFLICT (user_id) DO UPDATE
               SET granted = EXCLUDED.granted, version = EXCLUDED.version,
                   changed_at = EXCLUDED.changed_at
             WHERE tracking_consent.changed_at <= EXCLUDED.changed_at
            """)
        .param("u", user)
        .param("g", granted)
        .param("v", version)
        .param("t", Timestamp.from(event.occurredAt()))
        .update();
  }

  public boolean granted(UUID user) {
    return db.sql("SELECT granted FROM tracking_consent WHERE user_id = :u")
        .param("u", user)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  /** 403 tracking.consent-needed unless the person said yes. */
  public void require(UUID user) {
    if (!granted(user)) {
      throw new SolaceException(TrackingError.CONSENT_NEEDED);
    }
  }

  @Override
  public void erase(UUID user) {
    db.sql("DELETE FROM tracking_consent WHERE user_id = :u").param("u", user).update();
  }
}
