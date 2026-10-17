package care.solace.core.identity;

import care.solace.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** Settings and the {@code core.user} topic identity produces to. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentitySettings.class)
class IdentityConfiguration {

  @Bean
  KafkaAdmin.NewTopics userTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(UserRegistered.TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }
}
