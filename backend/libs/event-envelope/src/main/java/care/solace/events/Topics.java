package care.solace.events;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic declarations. Each service declares the topics it produces, each with its dead-letter
 * topic, as {@code KafkaAdmin.NewTopics} beans. The broker does not create topics on its own.
 */
public final class Topics {

  /** Suffix of a topic's dead-letter topic. */
  public static final String DEAD_LETTER_SUFFIX = ".dlt";

  private Topics() {}

  /** The topic and its dead-letter topic, with the same partition count. */
  public static List<NewTopic> withDeadLetter(String topic, int partitions, int replicas) {
    List<NewTopic> topics = new ArrayList<>();
    topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
    topics.add(
        TopicBuilder.name(deadLetterOf(topic)).partitions(partitions).replicas(replicas).build());
    return topics;
  }

  public static String deadLetterOf(String topic) {
    return topic + DEAD_LETTER_SUFFIX;
  }
}
