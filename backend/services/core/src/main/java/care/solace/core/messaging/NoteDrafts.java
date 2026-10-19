package care.solace.core.messaging;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** ai.note.drafted: tells the specialist a draft awaits their review (D57), once per event. */
@Component
class NoteDrafts {

  static final String CONSUMER = "core.note-drafts";

  /** ai.note.drafted v1, Core's own view of the payload. */
  record Drafted(UUID noteId, UUID bookingId, UUID specialistId) {}

  private final Notifier notifier;
  private final IdempotentConsumer once;

  NoteDrafts(Notifier notifier, IdempotentConsumer once) {
    this.notifier = notifier;
    this.once = once;
  }

  @KafkaListener(topics = "ai.note")
  void on(EventEnvelope event) {
    if (!"ai.note.drafted".equals(event.type())) {
      return;
    }
    Drafted drafted = event.payloadAs(Drafted.class);
    once.handle(
        CONSUMER,
        event,
        () ->
            notifier.push(
                drafted.specialistId(),
                "NOTE_DRAFT_READY",
                drafted.bookingId(),
                false,
                (l, z) -> new Object[0]));
  }
}
