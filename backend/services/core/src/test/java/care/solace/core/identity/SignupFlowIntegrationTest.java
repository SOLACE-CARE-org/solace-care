package care.solace.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Seeker sign-up end to end, the way the app does it: Core creates the account, Keycloak (real
 * realms, real code authenticator) sends and checks the code, and the first call to {@code /v1/me}
 * activates the account and publishes {@code core.user.registered}.
 */
@SpringBootTest(properties = "solace.grpc.client-registration=")
@AutoConfigureMockMvc
@Testcontainers
class SignupFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  static final KeycloakContainer KEYCLOAK =
      new KeycloakContainer("quay.io/keycloak/keycloak:26.3.1")
          .withRealmImportFiles("/solace-members-realm.json", "/solace-staff-realm.json")
          .withProviderLibsFrom(List.of(new File(System.getProperty("solace.providerJar"))))
          // Codes go to Keycloak's log here; CoreCodeSender has its own test.
          .withEnv("SOLACE_DEV_CODE_SENDER", "enabled")
          // Keycloak builds its providers on start; slow CI machines need more than 2 minutes.
          .withStartupTimeout(java.time.Duration.ofMinutes(5));

  static {
    KEYCLOAK.start();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ADULT = LocalDate.now().minusYears(30).toString();

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry properties) {
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

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired UnverifiedSignups unverified;

  // --- Sign-up -------------------------------------------------------------------------------

  @Test
  void seekerSignsUpVerifiesTheirPhoneAndFinishesSignUp() throws Exception {
    String phone = "+237670100001";
    signUp("Mireille", phone, "mireille@example.cm");

    String token = signIn(phone);
    JsonNode me = me(token);
    assertThat(me.path("role").asText()).isEqualTo("SEEKER");
    assertThat(me.path("displayName").asText()).isEqualTo("Mireille");
    assertThat(me.path("appLanguage").asText()).isEqualTo("en");
    assertThat(me.path("onboarding").path("preferences").asBoolean()).isFalse();
    assertThat(me.path("onboarding").path("privacy").asBoolean()).isFalse();
    String id = me.path("id").asText();

    String registered = firstEventOn("core.user", id, "core.user.registered");
    assertThat(registered)
        .contains("\"type\":\"core.user.registered\"")
        .contains("\"accountType\":\"SEEKER\"")
        .doesNotContain(phone)
        .doesNotContain("Mireille")
        .doesNotContain("mireille@");

    // Step 2: Make it yours.
    mvc.perform(
            put("/v1/me/preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"appLanguage":"fr","sessionLanguages":["en","fr"],
                     "helpTopics":["sleep","anxiety"],"specialistGender":"WOMAN"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appLanguage").value("fr"))
        .andExpect(jsonPath("$.helpTopics.length()").value(2))
        .andExpect(jsonPath("$.specialistGender").value("WOMAN"));

    // Step 3: privacy choices. Phase 1 offers AI assistance and AI session notes only.
    mvc.perform(get("/v1/me/consents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consents.length()").value(2))
        .andExpect(jsonPath("$.consents[0].purpose").value("AI_ASSIST"))
        .andExpect(jsonPath("$.consents[0].granted").value(false))
        .andExpect(jsonPath("$.consents[0].version").value("v1"));
    mvc.perform(
            put("/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"choices":[{"purpose":"AI_ASSIST","granted":true},
                                {"purpose":"AI_SCRIBE","granted":false}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consents[0].granted").value(true))
        .andExpect(jsonPath("$.consents[1].granted").value(false));
    assertThat(firstEventOn("core.consent", id, "\"purpose\":\"AI_ASSIST\""))
        .contains("\"purpose\":\"AI_ASSIST\"")
        .contains("\"granted\":true")
        .contains("\"locale\":\"fr\"");

    // A purpose from a later phase is refused.
    mvc.perform(
            put("/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choices\":[{\"purpose\":\"TRACKING\",\"granted\":true}]}"))
        .andExpect(status().isBadRequest());

    JsonNode after = me(token);
    assertThat(after.path("appLanguage").asText()).isEqualTo("fr");
    assertThat(after.path("onboarding").path("preferences").asBoolean()).isTrue();
    assertThat(after.path("onboarding").path("privacy").asBoolean()).isTrue();
  }

  @Test
  void signingUpAgainWithARegisteredNumberLooksTheSameAndChangesNothing() throws Exception {
    String phone = "+237670100002";
    signUp("Awa", phone, null);
    String token = signIn(phone);
    me(token);

    signUp("Someone else", phone, null);
    assertThat(me(token).path("displayName").asText()).isEqualTo("Awa");
  }

  @Test
  void anUnverifiedSignUpCanBeRedoneWithNewDetails() throws Exception {
    String phone = "+237670100003";
    signUp("Typo", phone, null);
    signUp("Paul", phone, null);
    assertThat(me(signIn(phone)).path("displayName").asText()).isEqualTo("Paul");
  }

  @Test
  void underEighteensAndOutdatedTermsAreRefused() throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                .content(
                    signupBody(
                        "Junior",
                        "+237670100004",
                        null,
                        LocalDate.now().minusYears(17).toString(),
                        "v1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.too-young"))
        .andExpect(
            jsonPath("$.title").value("Vous devez avoir 18 ans ou plus pour utiliser Solace."));
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody("Old app", "+237670100005", null, ADULT, "v0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.outdated-terms"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE phone IN ('+237670100004', '+237670100005')",
                Integer.class))
        .isZero();
  }

  @Test
  void choicesMustComeFromTheCatalogue() throws Exception {
    String phone = "+237670100006";
    signUp("Rose", phone, null);
    String token = signIn(phone);
    mvc.perform(
            put("/v1/me/preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"appLanguage":"en","sessionLanguages":["xx"],"helpTopics":[],
                     "specialistGender":"ANY"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.unknown-choice"));
  }

  // --- Which contact a sign-in proved ---------------------------------------------------------

  @Test
  void onlyTheContactTheCodeWentToCountsAsVerified() throws Exception {
    String phone = "+237670100007";
    String email = "verify.me@example.cm";
    signUp("Ngono", phone, email);

    me(signIn(email));
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, phone_verified_at, email_verified_at FROM user_account WHERE phone = ?",
            phone);
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("email_verified_at")).isNotNull();
    // The phone number was only typed in: no guest booking may be claimed through it yet.
    assertThat(row.get("phone_verified_at")).isNull();

    me(signIn(phone));
    assertThat(
            jdbc.queryForObject(
                "SELECT phone_verified_at FROM user_account WHERE phone = ?", Object.class, phone))
        .isNotNull();
  }

  // --- Who may call what ----------------------------------------------------------------------

  @Test
  void serviceAndStaffTokensAreNotMembers() throws Exception {
    String service = serviceToken("tracking-service", "tracking-service-dev-secret");
    mvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + service))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("account.not-a-member"));
  }

  @Test
  void onlyKeycloakMayHandOverCodesToSend() throws Exception {
    String body =
        """
        {"channel":"SMS","to":"+237670100008","code":"482913","expiresInMinutes":10,"locale":"fr"}
        """;
    String keycloak = serviceToken("keycloak-code-sender", "keycloak-code-sender-dev-secret");
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + keycloak)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    String tracking = serviceToken("tracking-service", "tracking-service-dev-secret");
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tracking)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void catalogueIsOpenAndInTheCallersLanguage() throws Exception {
    mvc.perform(get("/v1/catalogue/session-languages").header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("en"))
        .andExpect(jsonPath("$[0].name").value("Anglais"))
        .andExpect(jsonPath("$[1].name").value("Français"));
    mvc.perform(get("/v1/catalogue/help-topics").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(8))
        .andExpect(jsonPath("$[5].label").value("Women's health"));
  }

  // --- Unverified accounts expire --------------------------------------------------------------

  @Test
  void unverifiedAccountsAreRemovedWithTheirKeycloakUser() throws Exception {
    String phone = "+237670100009";
    signUp("Never came back", phone, null);
    String keycloakId =
        jdbc.queryForObject(
            "SELECT keycloak_id FROM user_account WHERE phone = ?", String.class, phone);
    jdbc.update(
        "UPDATE user_account SET created_at = ? WHERE phone = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))),
        phone);

    unverified.removeExpired();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE phone = ?", Integer.class, phone))
        .isZero();
    assertThat(
            KEYCLOAK
                .getKeycloakAdminClient()
                .realm("solace-members")
                .users()
                .search(phone, 0, 10)
                .stream()
                .filter(u -> keycloakId.equals(u.getId())))
        .isEmpty();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private void signUp(String name, String phone, String email) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(name, phone, email, ADULT, "v1")))
        .andExpect(status().isAccepted());
  }

  private static String signupBody(
      String name, String phone, String email, String dateOfBirth, String terms) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("displayName", name);
    body.put("phone", phone);
    if (email != null) {
      body.put("email", email);
    }
    body.put("dateOfBirth", dateOfBirth);
    body.put("termsVersion", terms);
    body.put("privacyNoticeVersion", "v1");
    body.put("appLanguage", "en");
    return JSON.writeValueAsString(body);
  }

  /** Asks Keycloak for a code, reads it from the log, and exchanges it for an access token. */
  private static String signIn(String contact) throws Exception {
    assertThat(tokenRequest(Map.of("username", contact)).statusCode()).isEqualTo(401);
    String code = lastCodeFor(mask(contact));
    HttpResponse<String> tokens = tokenRequest(Map.of("username", contact, "code", code));
    assertThat(tokens.statusCode()).as(tokens.body()).isEqualTo(200);
    return JSON.readTree(tokens.body()).path("access_token").asText();
  }

  private JsonNode me(String token) throws Exception {
    String body =
        mvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JSON.readTree(body);
  }

  private static HttpResponse<String> tokenRequest(Map<String, String> form) throws Exception {
    var all = new java.util.LinkedHashMap<String, String>(form);
    all.put("grant_type", "password");
    all.put("client_id", "solace-mobile");
    return formPost(
        KEYCLOAK.getAuthServerUrl() + "/realms/solace-members/protocol/openid-connect/token", all);
  }

  private static String serviceToken(String clientId, String secret) throws Exception {
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

  /** The first event on {@code topic} about {@code subjectId} that contains {@code marker}. */
  private static String firstEventOn(String topic, String subjectId, String marker) {
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
}
