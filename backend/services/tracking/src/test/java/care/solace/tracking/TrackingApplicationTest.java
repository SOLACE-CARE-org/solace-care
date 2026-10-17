package care.solace.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    properties = {"spring.grpc.server.inprocess.name=tracking-test", "spring.grpc.server.port=0"})
@AutoConfigureMockMvc
@Testcontainers
class TrackingApplicationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private final ManagedChannel channel = InProcessChannelBuilder.forName("tracking-test").build();

  @AfterEach
  void close() {
    channel.shutdownNow();
  }

  @Test
  void migrationsCreateTheSharedTables() {
    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class))
        .contains("audit_log", "event_publication", "processed_event");
  }

  @Test
  void healthIsOpenAndTheApiNeedsAToken() throws Exception {
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mvc.perform(get("/v1/anything")).andExpect(status().isUnauthorized());
  }

  @Test
  void pingAnswersCoreWithTheRightScope() {
    assertThat(stub("tracking:ping").ping(PingRequest.getDefaultInstance()).getService())
        .isEqualTo("tracking");
  }

  @Test
  void pingRefusesAnotherServicesScope() {
    assertThatThrownBy(() -> stub("ai:ping").ping(PingRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  private PingServiceGrpc.PingServiceBlockingStub stub(String scope) {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + scope);
    return PingServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  /** Stands in for Keycloak: the token text is the scope the caller holds. */
  @TestConfiguration
  static class FakeKeycloak {
    @Bean
    JwtDecoder solaceServiceJwtDecoder() {
      return token -> {
        if (token.isBlank()) {
          throw new BadJwtException("empty");
        }
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("service-account-core-service")
            .claim("client_id", "core-service")
            .claim("scope", token)
            .claim("aud", List.of("tracking"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }
  }
}
