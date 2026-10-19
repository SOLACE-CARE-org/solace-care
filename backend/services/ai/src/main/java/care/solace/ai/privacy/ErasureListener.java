package care.solace.ai.privacy;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Right to erasure (System Design; D40): when Core erases an account, AI deletes everything it
 * holds for that person, once per event.
 */
@Component
class ErasureListener {

  static final String CONSUMER = "ai.user-erased";

  private final IdempotentConsumer once;
  private final JdbcClient db;

  ErasureListener(IdempotentConsumer once, JdbcClient db) {
    this.once = once;
    this.db = db;
  }

  @KafkaListener(topics = "core.user")
  void on(EventEnvelope event) {
    if (!"core.user.erased".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    UUID subject = UUID.fromString(event.subjectId());
    once.handle(
        CONSUMER,
        event,
        () -> {
          db.sql("DELETE FROM safety_decision WHERE subject_id = :s").param("s", subject).update();
          // Model calls about them, with any kept text, and their consent mirror (D56).
          db.sql("DELETE FROM ai_call WHERE subject_id = :s").param("s", subject).update();
          db.sql("DELETE FROM ai_consent WHERE subject_id = :s").param("s", subject).update();
        });
  }
}
