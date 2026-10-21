package care.solace.core.messaging;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * From the AI service: ai.note.drafted tells the specialist a draft awaits their review, and
 * ai.homework.assigned tells the seeker they have a new task (D57). Once per event.
 */
@Component
class NoteDrafts {

  static final String CONSUMER = "core.note-drafts";
  static final String CONSUMER_HOMEWORK = "core.homework-assigned";

  /** ai.note.drafted v1, Core's own view of the payload. */
  record Drafted(UUID noteId, UUID bookingId, UUID specialistId) {}

  private final Notifier notifier;
  private final IdempotentConsumer once;

  NoteDrafts(Notifier notifier, IdempotentConsumer once) {
    this.notifier = notifier;
    this.once = once;
  }

  /** ai.homework.assigned v1. */
  record Assigned(UUID homeworkId, UUID bookingId) {}

  /** ai.homework.assigned: the seeker hears they have a new task (neutral copy). */
  @KafkaListener(topics = "ai.homework")
  void onHomework(EventEnvelope event) {
    if (!"ai.homework.assigned".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    Assigned assigned = event.payloadAs(Assigned.class);
    UUID seeker = UUID.fromString(event.subjectId());
    once.handle(
        CONSUMER_HOMEWORK,
        event,
        () ->
            notifier.push(
                seeker,
                "TASK_FROM_SPECIALIST",
                // The booking, so a tap opens that session; one push per session's homework.
                assigned.bookingId(),
                false,
                (l, z) -> new Object[0]));
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
