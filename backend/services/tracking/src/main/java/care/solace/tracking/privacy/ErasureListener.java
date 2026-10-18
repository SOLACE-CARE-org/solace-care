package care.solace.tracking.privacy;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Right to erasure (System Design; D40): Tracking deletes everything it holds for an erased person.
 * Before Phase 2 it holds no personal data, so it only records that the event was handled; each
 * tracking table adds its delete here when it ships.
 */
@Component
class ErasureListener {

  static final String CONSUMER = "tracking.user-erased";
  private static final Logger LOG = LoggerFactory.getLogger(ErasureListener.class);

  private final IdempotentConsumer once;

  ErasureListener(IdempotentConsumer once) {
    this.once = once;
  }

  @KafkaListener(topics = "core.user")
  void on(EventEnvelope event) {
    if (!"core.user.erased".equals(event.type())) {
      return;
    }
    once.handle(CONSUMER, event, () -> LOG.info("Erasure handled: eventId={}", event.eventId()));
  }
}
