package care.solace.tracking;

import care.solace.events.Topics;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** Topics Tracking produces to (Tracking Service Spec, Events). Nothing about single entries. */
@Configuration(proxyBeanMethods = false)
class TrackingTopics {

  @Bean
  KafkaAdmin.NewTopics trackingEventTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Stream.of("tracking.recommendation", "tracking.module", "tracking.nudge")
            .flatMap(t -> Topics.withDeadLetter(t, partitions, replicas).stream())
            .toArray(NewTopic[]::new));
  }
}
