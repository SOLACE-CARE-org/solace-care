package care.solace.tracking.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import care.solace.events.EventEnvelope;
import care.solace.events.Events;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=tracking-listener-test",
      "spring.grpc.server.port=0",
      "logging.level.org.springframework.kafka.listener=DEBUG"
    })
@Testcontainers
class SystemPingListenerTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  @Autowired KafkaTemplate<Object, Object> kafka;
  @Autowired JdbcTemplate jdbc;

  @Test
  void pingFromCoreIsHandledOnceEvenWhenDeliveredTwice() {
    EventEnvelope ping =
        Events.platformEnvelope(
            "core.system.pinged", 1, Map.of("trackingReachable", true, "aiReachable", true));

    kafka.send("core.system", ping.partitionKey(), ping);
    kafka.send("core.system", ping.partitionKey(), ping);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                            Long.class,
                            SystemPingListener.CONSUMER,
                            ping.eventId()))
                    .isEqualTo(1L));
  }

  /** Core owns and declares this topic; the test stands in for it. */
  @TestConfiguration
  static class CoreTopic {
    @Bean
    NewTopic coreSystem() {
      return TopicBuilder.name("core.system").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic coreSystemDeadLetters() {
      return TopicBuilder.name("core.system.dlt").partitions(1).replicas(1).build();
    }
  }
}
