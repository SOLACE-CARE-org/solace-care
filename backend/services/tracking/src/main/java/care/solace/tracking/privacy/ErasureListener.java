package care.solace.tracking.privacy;

import care.solace.events.EventEnvelope;
import care.solace.events.Events;
import care.solace.events.IdempotentConsumer;
import care.solace.events.Topics;
import care.solace.tracking.PersonalData;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Right to erasure (System Design; D40): on core.user.erased, Tracking deletes every row and note
 * it holds for the person, then confirms with tracking.user.erased for Core's audit log.
 */
@Component
class ErasureListener {

  static final String CONSUMER = "tracking.user-erased";
  static final String TOPIC = "tracking.user";

  /** Payload of tracking.user.erased v1. */
  record Erased() {}

  private final IdempotentConsumer once;
  private final List<PersonalData> holders;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;

  ErasureListener(
      IdempotentConsumer once,
      List<PersonalData> holders,
      ApplicationEventPublisher events,
      TransactionTemplate transactions) {
    this.once = once;
    this.holders = holders;
    this.events = events;
    this.transactions = transactions;
  }

  @KafkaListener(topics = "core.user")
  void on(EventEnvelope event) {
    if (!"core.user.erased".equals(event.type())) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () ->
            transactions.executeWithoutResult(
                s -> {
                  UUID user = UUID.fromString(event.subjectId());
                  holders.forEach(h -> h.erase(user));
                  events.publishEvent(
                      Events.envelope(
                          "tracking.user.erased",
                          1,
                          user.toString(),
                          event.locale(),
                          new Erased()));
                }));
  }

  @Configuration(proxyBeanMethods = false)
  static class Topic {
    @Bean
    KafkaAdmin.NewTopics trackingUserTopics(
        @Value("${solace.events.partitions:3}") int partitions,
        @Value("${solace.events.replicas:1}") int replicas) {
      return new KafkaAdmin.NewTopics(
          Topics.withDeadLetter(TOPIC, partitions, replicas).toArray(NewTopic[]::new));
    }
  }
}
