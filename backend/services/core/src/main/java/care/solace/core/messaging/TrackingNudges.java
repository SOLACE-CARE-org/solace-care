package care.solace.core.messaging;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Sends the one tracking push Tracking asked for (tracking.nudge.requested; D52). Tracking keeps
 * the budget: at most one a day, never SMS, stopping after 5 ignored days. An invite's push is "new
 * to look at", in place of that day's reminder; both are neutral (notification-copy.md).
 */
@Component
class TrackingNudges {

  static final String CONSUMER = "core.tracking-nudges";

  /** Payload of tracking.nudge.requested v1. */
  record NudgeRequested(String kind) {}

  private final Notifier notifier;
  private final IdempotentConsumer once;

  TrackingNudges(Notifier notifier, IdempotentConsumer once) {
    this.notifier = notifier;
    this.once = once;
  }

  @KafkaListener(topics = "tracking.nudge")
  void on(EventEnvelope event) {
    if (!"tracking.nudge.requested".equals(event.type())) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () -> {
          var nudge = event.payloadAs(NudgeRequested.class);
          UUID account = UUID.fromString(event.subjectId());
          // The reminder goes at the time the person chose; a "new to look at" push can wait for
          // the end of quiet hours.
          boolean invite = "INVITE".equals(nudge.kind());
          notifier.push(
              account,
              invite ? "TRACKING_NEW" : "TRACKING_REMINDER",
              null,
              invite,
              (locale, zone) -> new Object[0]);
        });
  }
}
