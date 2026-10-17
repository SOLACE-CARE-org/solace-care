package care.solace.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingResponse;
import care.solace.contracts.platform.v1.PingServiceGrpc;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Core's half of the end-to-end slice: Tracking and AI stand-ins answer over in-process gRPC, and
 * the event lands on Kafka. The full slice with real services runs in infra/scripts/e2e-ping.sh.
 */
@SpringBootTest(properties = {"solace.grpc.client-registration="})
@AutoConfigureMockMvc
@Testcontainers
class SystemPingTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  static final Server TRACKING = standIn("tracking-standin", "tracking");
  static Server ai = standIn("ai-standin", "ai");

  // Spring gRPC only resolves named channels over the network, so the two stubs are swapped for
  // in-process ones here. The real channels run in infra/scripts/e2e-ping.sh.
  @TestBean(name = "trackingPing", methodName = "trackingStandIn")
  PingServiceGrpc.PingServiceBlockingStub trackingPing;

  @TestBean(name = "aiPing", methodName = "aiStandIn")
  PingServiceGrpc.PingServiceBlockingStub aiPing;

  static PingServiceGrpc.PingServiceBlockingStub trackingStandIn() {
    return PingServiceGrpc.newBlockingStub(
        InProcessChannelBuilder.forName("tracking-standin").build());
  }

  static PingServiceGrpc.PingServiceBlockingStub aiStandIn() {
    return PingServiceGrpc.newBlockingStub(InProcessChannelBuilder.forName("ai-standin").build());
  }

  @Autowired MockMvc mvc;

  @AfterAll
  static void stop() {
    TRACKING.shutdownNow();
    ai.shutdownNow();
  }

  @Test
  void pingReachesBothServicesAndPublishesTheEvent() throws Exception {
    mvc.perform(get("/v1/system/ping").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.core").value(true))
        .andExpect(jsonPath("$.tracking").value(true))
        .andExpect(jsonPath("$.ai").value(true));

    String event = firstEventOn("core.system", "\"aiReachable\":true");
    assertThat(event)
        .contains("\"type\":\"core.system.pinged\"")
        .contains("\"trackingReachable\":true");
  }

  @Test
  void serviceThatIsDownReportsFalseAndThePingStillAnswers() throws Exception {
    ai.shutdownNow().awaitTermination();
    try {
      mvc.perform(get("/v1/system/ping").with(jwt()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tracking").value(true))
          .andExpect(jsonPath("$.ai").value(false));
      assertThat(firstEventOn("core.system", "\"aiReachable\":false")).isNotNull();
    } finally {
      ai = standIn("ai-standin", "ai");
    }
  }

  @Test
  void pingNeedsASignedInCaller() throws Exception {
    mvc.perform(get("/v1/system/ping")).andExpect(status().isUnauthorized());
  }

  private static String firstEventOn(String topic, String marker) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(topic));
      return await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                for (ConsumerRecord<String, String> record :
                    consumer.poll(Duration.ofMillis(500))) {
                  if (record.value().contains(marker)) {
                    return record.value();
                  }
                }
                return null;
              },
              value -> value != null);
    }
  }

  private static Server standIn(String name, String service) {
    try {
      return InProcessServerBuilder.forName(name)
          .addService(
              new PingServiceGrpc.PingServiceImplBase() {
                @Override
                public void ping(PingRequest request, StreamObserver<PingResponse> responses) {
                  responses.onNext(PingResponse.newBuilder().setService(service).build());
                  responses.onCompleted();
                }
              })
          .build()
          .start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
