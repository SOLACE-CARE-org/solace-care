package care.solace.core.privacy;

import care.solace.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** Settings and the {@code core.consent} topic. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacySettings.class)
class PrivacyConfiguration {

  @Bean
  KafkaAdmin.NewTopics consentTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(ConsentChanged.TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }
}
