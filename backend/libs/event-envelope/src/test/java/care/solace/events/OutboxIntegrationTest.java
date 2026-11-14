package care.solace.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The outbox and consumer path against real PostgreSQL and Kafka. */
@SpringBootTest(
    classes = OutboxIntegrationTest.App.class,
    properties = {
      "spring.application.name=events-test",
      // As in every service: listeners must still get platform threads.
      "spring.threads.virtual.enabled=true"
    })
@Testcontainers
class OutboxIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired Producer producer;
  @Autowired Received received;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<Object, Object> kafka;

  @Test
  void eventReachesKafkaAfterCommitOnItsTopicKeyedBySubject() {
    EventEnvelope sent =
        producer.publish(
            Events.envelope("core.test.happened", 1, "seeker-1", "fr", Map.of("n", 1)), false);

    ConsumerRecord<String, EventEnvelope> record = received.awaitRecord(sent.eventId());
    // Services declare listeners with an EventEnvelope parameter; it must arrive as one too.
    await().atMost(WAIT).until(() -> received.envelopeParameters.contains(sent.eventId()));
    assertThat(record.topic()).isEqualTo("core.test");
    assertThat(record.key()).isEqualTo("seeker-1");
    assertThat(record.value()).isEqualTo(sent);
    assertThat(record.value().payloadAs(Counter.class)).isEqualTo(new Counter(1));
  }

  @Test
  void wireFormatMatchesTheContractSchema() throws Exception {
    EventEnvelope sent =
        producer.publish(
            Events.platformEnvelope(
                "core.system.pinged", 1, Map.of("trackingReachable", true, "aiReachable", false)),
            false);

    String json = rawValues("core.system", sent.eventId().toString());

    JsonNode wire = new ObjectMapper().readTree(json);
    Set<ValidationMessage> problems =
        contractSchema("core/system.pinged.v1.schema.json").validate(wire);
    assertThat(problems).isEmpty();
    assertThat(wire.get("occurredAt").isTextual()).isTrue();
  }

  @Test
  void rolledBackChangePublishesNothing() {
    EventEnvelope envelope = Events.envelope("core.test.happened", 1, "seeker-2", "en", Map.of());

    assertThatThrownBy(() -> producer.publish(envelope, true))
        .isInstanceOf(IllegalStateException.class);

    EventEnvelope later =
        producer.publish(
            Events.envelope("core.test.happened", 1, "seeker-2", "en", Map.of()), false);
    received.awaitRecord(later.eventId());
    assertThat(received.recordFor(envelope.eventId())).isNull();
  }

  @Test
  void outboxRowIsDeletedOnceKafkaHasTheEvent() {
    EventEnvelope sent =
        producer.publish(
            Events.envelope("core.test.happened", 1, "seeker-3", "en", Map.of()), false);
    received.awaitRecord(sent.eventId());

    await()
        .atMost(WAIT)
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                    .isZero());
  }

  @Test
  void redeliveredEventIsHandledOnce() {
    EventEnvelope event = Events.envelope("core.test.happened", 1, "seeker-4", "en", Map.of());

    kafka.send("core.test", event.partitionKey(), event);
    kafka.send("core.test", event.partitionKey(), event);

    await().atMost(WAIT).until(() -> received.deliveries(event.eventId()) == 2);
    assertThat(received.handled(event.eventId())).isEqualTo(1);
  }

  @Test
  void failingAndUnreadableRecordsEndUpInTheDeadLetterTopic() {
    EventEnvelope poison = Events.envelope("core.poison.happened", 1, "seeker-5", "en", Map.of());
    kafka.send("core.poison", poison.partitionKey(), poison);
    kafka.send("core.poison", "seeker-5", "not an envelope");

    assertThat(rawValues("core.poison.dlt", poison.eventId().toString())).isNotNull();
    assertThat(rawValues("core.poison.dlt", "not an envelope")).isNotNull();
  }

  @Test
  void listenersRunOnPlatformThreadsWhileVirtualThreadsAreOn() {
    EventEnvelope sent =
        producer.publish(
            Events.envelope("core.test.happened", 1, "seeker-6", "en", Map.of()), false);
    received.awaitRecord(sent.eventId());

    assertThat(received.virtualThreads).isFalse();
  }

  @Test
  void envelopeRejectsValuesTheSchemaWouldRefuse() {
    assertThatThrownBy(() -> Events.envelope("booking.confirmed", 1, null, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Events.envelope("core.booking.confirmed", 1, "s", "de", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Reads a topic from the start as raw bytes and returns the first value containing {@code
   * marker}.
   */
  private static String rawValues(String topic, String marker) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "raw-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(topic));
      return await()
          .atMost(WAIT)
          .until(
              () -> {
                for (ConsumerRecord<String, String> record :
                    consumer.poll(Duration.ofMillis(500))) {
                  if (record.value() != null && record.value().contains(marker)) {
                    return record.value();
                  }
                }
                return null;
              },
              value -> value != null);
    }
  }

  private static JsonSchema contractSchema(String path) {
    String contracts = new File(System.getProperty("solace.contracts.events")).toURI().toString();
    JsonSchemaFactory factory =
        JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder ->
                builder.schemaMappers(
                    mappers ->
                        mappers.mapPrefix("https://schemas.solace.care/events/", contracts)));
    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    return factory.getSchema(
        SchemaLocation.of("https://schemas.solace.care/events/" + path), config);
  }

  record Counter(int n) {}

  @Configuration
  @EnableAutoConfiguration
  @Import({Producer.class, Received.class})
  static class App {
    @Bean
    KafkaAdmin.NewTopics topics() {
      List<NewTopic> topics = new ArrayList<>();
      topics.addAll(Topics.withDeadLetter("core.test", 3, 1));
      topics.addAll(Topics.withDeadLetter("core.system", 1, 1));
      topics.addAll(Topics.withDeadLetter("core.poison", 1, 1));
      return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
  }

  static class Producer {
    private final ApplicationEventPublisher events;

    Producer(ApplicationEventPublisher events) {
      this.events = events;
    }

    @Transactional
    public EventEnvelope publish(EventEnvelope envelope, boolean failAfterwards) {
      events.publishEvent(envelope);
      if (failAfterwards) {
        throw new IllegalStateException("the change failed");
      }
      return envelope;
    }
  }

  static class Received {
    private final IdempotentConsumer once;
    private final Map<UUID, ConsumerRecord<String, EventEnvelope>> records =
        new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deliveries = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> handled = new ConcurrentHashMap<>();
    volatile boolean virtualThreads;

    Received(IdempotentConsumer once) {
      this.once = once;
    }

    @KafkaListener(
        topics = {"core.test", "core.system"},
        groupId = "typed")
    void on(ConsumerRecord<String, EventEnvelope> record) {
      if (Thread.currentThread().isVirtual()) {
        virtualThreads = true;
      }
      UUID id = record.value().eventId();
      deliveries.merge(id, 1, Integer::sum);
      once.handle(
          "test.counter",
          record.value(),
          () -> {
            records.put(id, record);
            handled.merge(id, 1, Integer::sum);
          });
    }

    final java.util.Set<UUID> envelopeParameters = ConcurrentHashMap.newKeySet();

    @KafkaListener(topics = "core.test", groupId = "as-parameter")
    void asParameter(EventEnvelope event) {
      envelopeParameters.add(event.eventId());
    }

    @KafkaListener(topics = "core.poison", groupId = "poison")
    void poison(EventEnvelope event) {
      throw new IllegalStateException("cannot handle " + event.type());
    }

    ConsumerRecord<String, EventEnvelope> awaitRecord(UUID eventId) {
      return await().atMost(WAIT).until(() -> records.get(eventId), r -> r != null);
    }

    ConsumerRecord<String, EventEnvelope> recordFor(UUID eventId) {
      return records.get(eventId);
    }

    int deliveries(UUID eventId) {
      return deliveries.getOrDefault(eventId, 0);
    }

    int handled(UUID eventId) {
      return handled.getOrDefault(eventId, 0);
    }
  }
}
