package care.solace.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.CreateContainerCmd;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Core with everything a member flow touches, started once per test run and shared by every
 * subclass (and so one Spring context): PostgreSQL, Kafka, MinIO, and real Keycloak with the real
 * realms and the code authenticator, whose codes are read from its log.
 */
@SpringBootTest(properties = "solace.grpc.client-registration=")
@AutoConfigureMockMvc
public abstract class MemberStackTest {

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  /** The image infra/docker-compose.yml runs: MinIO no longer publishes to Docker Hub. */
  protected static final MinIOContainer MINIO =
      new MinIOContainer(
              DockerImageName.parse(
                      "cgr.dev/chainguard/minio@sha256:c93745c56fccdaf0e5dcb88999a96a65a136428beb045ef5d546255a80d747a7")
                  .asCompatibleSubstituteFor("minio/minio"))
          .withCreateContainerCmdModifier(new RunAsRoot());

  protected static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.3.1")
          .withRealmImportFiles("/solace-members-realm.json", "/solace-staff-realm.json")
          .withProviderLibsFrom(List.of(new File(System.getProperty("solace.providerJar"))))
          // Codes go to Keycloak's log here; CoreCodeSender has its own test.
          .withEnv("SOLACE_DEV_CODE_SENDER", "enabled")
          // Keycloak builds its providers on start; slow CI machines need more than 2 minutes.
          .withStartupTimeout(Duration.ofMinutes(5));

  static {
    // Started together on Testcontainers' own threads: a parallel stream here would deadlock on
    // this class's initialisation.
    Startables.deepStart(POSTGRES, KAFKA, MINIO, KEYCLOAK).join();
    try (S3Client s3 = s3()) {
      s3.createBucket(b -> b.bucket("solace-credentials"));
    }
  }

  /**
   * MinIO runs as root, as in infra/docker-compose.yml. A class rather than a lambda: containers
   * start on Testcontainers' threads while this class is still initialising, and a lambda defined
   * here would wait for that to finish.
   */
  private static final class RunAsRoot implements Consumer<CreateContainerCmd> {
    @Override
    public void accept(CreateContainerCmd cmd) {
      cmd.withUser("0");
    }
  }

  protected static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void stack(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    properties.add("solace.storage.endpoint", MINIO::getS3URL);
    properties.add("solace.storage.access-key", MINIO::getUserName);
    properties.add("solace.storage.secret-key", MINIO::getPassword);
    String base = KEYCLOAK.getAuthServerUrl().replaceAll("/$", "");
    String members = base + "/realms/solace-members";
    String staff = base + "/realms/solace-staff";
    properties.add("solace.security.issuers", () -> members + "," + staff);
    properties.add("solace.security.service-issuer", () -> staff);
    properties.add("solace.identity.members-issuer", () -> members);
    properties.add("solace.identity.keycloak.admin-url", () -> base);
    properties.add(
        "spring.security.oauth2.client.provider.keycloak-members.token-uri",
        () -> members + "/protocol/openid-connect/token");
    properties.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> staff + "/protocol/openid-connect/token");
  }

  /** A client for the test's own look at what Core stored. */
  protected static S3Client s3() {
    return S3Client.builder()
        .endpointOverride(URI.create(MINIO.getS3URL()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
        .forcePathStyle(true)
        .build();
  }

  /** Asks Keycloak for a code, reads it from the log, and exchanges it for an access token. */
  protected static String signIn(String contact) throws Exception {
    assertThat(tokenRequest(Map.of("username", contact)).statusCode()).isEqualTo(401);
    String code = lastCodeFor(mask(contact));
    HttpResponse<String> tokens = tokenRequest(Map.of("username", contact, "code", code));
    assertThat(tokens.statusCode()).as(tokens.body()).isEqualTo(200);
    return JSON.readTree(tokens.body()).path("access_token").asText();
  }

  protected static String serviceToken(String clientId, String secret) throws Exception {
    HttpResponse<String> response =
        formPost(
            KEYCLOAK.getAuthServerUrl() + "/realms/solace-staff/protocol/openid-connect/token",
            Map.of(
                "grant_type",
                "client_credentials",
                "client_id",
                clientId,
                "client_secret",
                secret));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body()).path("access_token").asText();
  }

  /** The first event on {@code topic} about {@code subjectId} that contains {@code marker}. */
  protected static String firstEventOn(String topic, String subjectId, String marker) {
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
                  if (subjectId.equals(record.key()) && record.value().contains(marker)) {
                    return record.value();
                  }
                }
                return null;
              },
              value -> value != null);
    }
  }

  private static HttpResponse<String> tokenRequest(Map<String, String> form) throws Exception {
    var all = new java.util.LinkedHashMap<String, String>(form);
    all.put("grant_type", "password");
    all.put("client_id", "solace-mobile");
    return formPost(
        KEYCLOAK.getAuthServerUrl() + "/realms/solace-members/protocol/openid-connect/token", all);
  }

  private static HttpResponse<String> formPost(String url, Map<String, String> form)
      throws Exception {
    String encoded =
        form.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url.replace("//realms", "/realms")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** As the authenticator masks contacts in its log line. */
  private static String mask(String contact) {
    if (contact.startsWith("+")) {
      return contact.substring(0, 4)
          + "*".repeat(contact.length() - 6)
          + contact.substring(contact.length() - 2);
    }
    return contact.charAt(0) + "***" + contact.substring(contact.indexOf('@'));
  }

  private static String lastCodeFor(String masked) {
    Pattern line = Pattern.compile("sign-in code (\\d+) for " + Pattern.quote(masked) + "(?!\\S)");
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              Matcher m = line.matcher(KEYCLOAK.getLogs());
              String last = null;
              while (m.find()) {
                last = m.group(1);
              }
              return last;
            },
            code -> code != null);
  }
}
