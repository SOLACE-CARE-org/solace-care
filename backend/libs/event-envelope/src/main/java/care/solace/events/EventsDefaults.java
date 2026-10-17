package care.solace.events;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Kafka and outbox settings every service shares, at the lowest precedence:
 *
 * <ul>
 *   <li>envelopes travel as JSON without Java type headers, so services never depend on each
 *       other's classes;
 *   <li>producers wait for all replicas and are idempotent;
 *   <li>a record that cannot be read is handed to the error handler (and the dead-letter topic)
 *       instead of blocking the partition;
 *   <li>Kafka sends and receives join the current trace;
 *   <li>the outbox re-sends anything left over after a restart and deletes rows once sent.
 * </ul>
 */
public class EventsDefaults implements EnvironmentPostProcessor {

  static final String SOURCE_NAME = "solaceEventsDefaults";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    Map<String, Object> defaults = new HashMap<>();
    defaults.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    defaults.put("spring.kafka.producer.value-serializer", EventJsonSerializer.class.getName());
    defaults.put("spring.kafka.producer.acks", "all");
    defaults.put("spring.kafka.producer.properties.enable.idempotence", "true");

    defaults.put(
        "spring.kafka.consumer.key-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    defaults.put(
        "spring.kafka.consumer.value-deserializer",
        "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
    defaults.put(
        "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class",
        "org.springframework.kafka.support.serializer.JsonDeserializer");
    defaults.put(
        "spring.kafka.consumer.properties.spring.json.value.default.type",
        EventEnvelope.class.getName());
    defaults.put("spring.kafka.consumer.properties.spring.json.use.type.headers", "false");
    defaults.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    defaults.put("spring.kafka.consumer.group-id", "${spring.application.name:solace}");

    defaults.put("spring.kafka.template.observation-enabled", "true");
    defaults.put("spring.kafka.listener.observation-enabled", "true");

    // Spring Modulith would otherwise register a byte-array JSON message converter that every
    // listener picks up; our serializer and deserializer handle JSON end to end instead.
    defaults.put("spring.modulith.events.kafka.enable-json", "false");
    defaults.put("spring.modulith.events.republish-outstanding-events-on-restart", "true");
    defaults.put("spring.modulith.events.completion-mode", "delete");
    defaults.put("spring.modulith.events.jdbc.schema-initialization.enabled", "false");

    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
  }
}
