package care.solace.tracking.events;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Last hop of the Phase 0 end-to-end slice: consumes {@code core.system.pinged} from Core's {@code
 * core.system} topic, once per event id. Its span closes the trace that started at the gateway.
 */
@Component
class SystemPingListener {

  static final String CONSUMER = "tracking.system-ping";
  private static final Logger LOG = LoggerFactory.getLogger(SystemPingListener.class);

  private final IdempotentConsumer once;

  SystemPingListener(IdempotentConsumer once) {
    this.once = once;
  }

  @KafkaListener(topics = "core.system")
  void on(EventEnvelope event) {
    if (!"core.system.pinged".equals(event.type())) {
      return;
    }
    boolean handled =
        once.handle(
            CONSUMER,
            event,
            () -> {
              Ping ping = event.payloadAs(Ping.class);
              LOG.info(
                  "System ping received: eventId={} trackingReachable={} aiReachable={}",
                  event.eventId(),
                  ping.trackingReachable(),
                  ping.aiReachable());
            });
    if (!handled) {
      LOG.info("System ping already handled: eventId={}", event.eventId());
    }
  }

  /** Tracking's own view of the payload; it never shares Core's classes (D2). */
  record Ping(boolean trackingReachable, boolean aiReachable) {}
}
