package care.solace.tracking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Tracking with its own PostgreSQL, shared by the integration tests (one Spring context). Kafka is
 * not started: listeners are exercised by calling them, and outgoing events are read from the
 * outbox table.
 */
@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=tracking-stack",
      "spring.grpc.server.port=0",
      "spring.kafka.listener.auto-startup=false",
      "solace.grpc.client-registration="
    })
@AutoConfigureMockMvc
public abstract class TrackingStackTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** The image infra/docker-compose.yml runs, as in Core's tests. */
  static final MinIOContainer MINIO =
      new MinIOContainer(
              DockerImageName.parse(
                      "cgr.dev/chainguard/minio@sha256:c93745c56fccdaf0e5dcb88999a96a65a136428beb045ef5d546255a80d747a7")
                  .asCompatibleSubstituteFor("minio/minio"))
          .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"));

  static {
    POSTGRES.start();
    MINIO.start();
    try (var s3 =
        S3Client.builder()
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .forcePathStyle(true)
            .build()) {
      s3.createBucket(b -> b.bucket("solace-module-images"));
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry properties) {
    properties.add("solace.storage.endpoint", MINIO::getS3URL);
    properties.add("solace.storage.access-key", MINIO::getUserName);
    properties.add("solace.storage.secret-key", MINIO::getPassword);
  }

  protected static final ObjectMapper JSON = new ObjectMapper();
  protected static final String MEMBERS = "http://localhost:8180/realms/solace-members";
  protected static final String STAFF = "http://localhost:8180/realms/solace-staff";

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcTemplate jdbc;

  /** A seeker's token from the members realm; the account id is the username (D49). */
  protected static RequestPostProcessor seeker(UUID id) {
    return member(id, "SEEKER");
  }

  protected static RequestPostProcessor specialist(UUID id) {
    return member(id, "SPECIALIST");
  }

  private static RequestPostProcessor member(UUID id, String role) {
    return jwt()
        .jwt(
            t ->
                t.issuer(MEMBERS)
                    .subject("kc-" + id)
                    .claim("preferred_username", id.toString())
                    .claim("realm_access", Map.of("roles", List.of(role))));
  }

  protected static RequestPostProcessor staff(String id, String name, String... roles) {
    return jwt()
        .jwt(
            t ->
                t.issuer(STAFF)
                    .subject(id)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  /** Records the person's yes to tracking, as core.consent.changed would. */
  protected void consent(UUID user) {
    jdbc.update(
        "INSERT INTO tracking_consent (user_id, granted, version, changed_at) VALUES (?, true, 'v1', now())"
            + " ON CONFLICT (user_id) DO UPDATE SET granted = true",
        user);
  }

  protected static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(
        result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
