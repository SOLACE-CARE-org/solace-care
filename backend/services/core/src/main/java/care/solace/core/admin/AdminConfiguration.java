package care.solace.core.admin;

import care.solace.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** The {@code core.config} topic Tracking and AI listen to for setting and flag changes (D42). */
@Configuration(proxyBeanMethods = false)
class AdminConfiguration {

  static final String TOPIC = "core.config";

  @Bean
  KafkaAdmin.NewTopics configTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }
}
