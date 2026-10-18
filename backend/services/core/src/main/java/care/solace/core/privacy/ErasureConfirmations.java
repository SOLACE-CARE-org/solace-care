package care.solace.core.privacy;

import care.solace.audit.AuditLog;
import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Records that Tracking finished an erasure (tracking.user.erased) in the audit log (D40, D49). */
@Component
class ErasureConfirmations {

  static final String CONSUMER = "core.tracking-erased";

  private final IdempotentConsumer once;
  private final AuditLog audit;

  ErasureConfirmations(IdempotentConsumer once, AuditLog audit) {
    this.once = once;
    this.audit = audit;
  }

  @KafkaListener(topics = "tracking.user")
  void on(EventEnvelope event) {
    if (!"tracking.user.erased".equals(event.type())) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () ->
            audit.adminAction(
                "privacy.erasure.tracking-done", "account", event.subjectId(), Map.of()));
  }
}
