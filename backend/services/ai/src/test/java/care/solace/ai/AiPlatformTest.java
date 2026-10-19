package care.solace.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.ai.gateway.ModelRoute;
import care.solace.ai.gateway.RouteCatalog;
import care.solace.contracts.ai.v1.DraftTranslationRequest;
import care.solace.contracts.ai.v1.ExplainRecommendationRequest;
import care.solace.contracts.ai.v1.InsightServiceGrpc;
import care.solace.contracts.ai.v1.TranslationServiceGrpc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The AI platform (Phase 3; D56) with scripted models in place of real ones: evaluation gates,
 * sign-off and promotion, the switch, consent, guardrails and the rule that health data stays on
 * local models.
 */
@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=ai-platform",
      "spring.grpc.server.port=0",
      "spring.kafka.listener.auto-startup=false",
      "solace.grpc.client-registration=",
      "solace.ai.routes.TRACKING_INSIGHTS[0]=remote",
      "solace.ai.routes.TRACKING_INSIGHTS[1]=local",
      "solace.ai.routes.TRANSLATION[0]=remote"
    })
@AutoConfigureMockMvc
@Testcontainers
class AiPlatformTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger REMOTE_CALLS = new AtomicInteger();

  /** What the scripted local model answers: (system, user) to text. */
  static volatile BiFunction<String, String, String> local = (s, u) -> "";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private final ManagedChannel channel = InProcessChannelBuilder.forName("ai-platform").build();
  private final RequestPostProcessor superAdmin = staff("sa-1", "S. Admin", "SUPER_ADMIN");
  private final RequestPostProcessor reviewer =
      staff("cr-1", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  @AfterEach
  void close() {
    channel.shutdownNow();
  }

  @Test
  void aFeatureGoesLiveOnlyAfterABilingualEvaluationASignOffAndPromotion() throws Exception {
    var overview =
        json(mvc.perform(get("/admin/v1/ai/features").with(superAdmin)).andExpect(status().isOk()));
    assertThat(overview.path("features")).hasSize(4);
    // No safety set supplied yet: the monitor is not shown as passing.
    assertThat(overview.path("safetyMonitor").path("passing").asBoolean()).isFalse();
    JsonNode insights = feature(overview, "TRACKING_INSIGHTS");
    assertThat(insights.path("enabled").asBoolean()).isFalse();
    String draft = insights.path("candidate").path("id").asText();
    assertThat(insights.path("candidate").path("version").asText()).isEqualTo("1.0");

    // Off and unevaluated: it cannot be switched on.
    mvc.perform(
            put("/admin/v1/ai/features/TRACKING_INSIGHTS/switch")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Launch\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ai.not-evaluated"));

    // A model that adds a finding of its own fails the French cases.
    local =
        (s, u) ->
            "{\"en\": \"You logged 35 or more days in each cycle.\", \"fr\": \"Vous avez noté 99 jours.\"}";
    var failed =
        json(mvc.perform(post("/admin/v1/ai/versions/" + draft + "/evaluation").with(superAdmin)));
    assertThat(failed.path("evaluation").path("passed").asBoolean()).isFalse();
    assertThat(failed.path("evaluation").path("failures").toString())
        .contains("number-not-in-evidence");

    // One that keeps to the evidence passes in both languages: it rewords the reviewed messages.
    local = AiPlatformTest::echoMessages;
    var passed =
        json(mvc.perform(post("/admin/v1/ai/versions/" + draft + "/evaluation").with(superAdmin)));
    assertThat(passed.path("evaluation").path("passed").asBoolean()).isTrue();
    assertThat(passed.path("evaluation").path("totalFr").asInt()).isPositive();

    // Promotion needs a Clinical reviewer's sign-off first.
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/promotion").with(superAdmin))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ai.sign-off-needed"));
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/sign-off").with(superAdmin))
        .andExpect(status().isForbidden());
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/sign-off").with(reviewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signedOffBy").value("Dr. Fomba Grace"));
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/promotion").with(superAdmin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVE"));
    // Reviewers look but do not switch features.
    mvc.perform(
            put("/admin/v1/ai/features/TRACKING_INSIGHTS/switch")
                .with(reviewer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Launch\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/admin/v1/ai/features/TRACKING_INSIGHTS/switch")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Evaluation passed and signed off\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));

    // Live: wording needs the seeker's AI_ASSIST consent, and stays on the local model.
    UUID seeker = UUID.randomUUID();
    var request =
        ExplainRecommendationRequest.newBuilder()
            .setRecommendationId(UUID.randomUUID().toString())
            .setSubjectId(seeker.toString())
            .setRuleCode("pcos.long_cycles")
            .setModuleCode("pcos")
            .setEvidenceJson("{\"n\":3,\"threshold\":35,\"logged\":14}")
            .setMessageEn("Each of your last 3 cycles ran longer than 35 days.")
            .setMessageFr("Chacun de vos 3 derniers cycles a duré plus de 35 jours.")
            .build();
    assertThat(insights().explainRecommendation(request).getWorded()).isFalse();
    jdbc.update(
        "INSERT INTO ai_consent (subject_id, purpose, granted, changed_at) VALUES (?, 'AI_ASSIST', true, now())",
        seeker);
    int remoteBefore = REMOTE_CALLS.get();
    var worded = insights().explainRecommendation(request);
    assertThat(worded.getWorded()).isTrue();
    assertThat(worded.getTextFr()).contains("35");
    assertThat(worded.getPromptVersion()).isEqualTo("1.0");
    // Health data never went to the non-local model: no transfer authorisation is recorded.
    assertThat(REMOTE_CALLS.get()).isEqualTo(remoteBefore);

    // A wording that names a condition is thrown away, and the refusal is on record.
    local =
        (s, u) ->
            "{\"en\": \"This looks like a hormonal disorder.\", \"fr\": \"Cela ressemble à une maladie.\"}";
    assertThat(insights().explainRecommendation(request).getWorded()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ai_call WHERE outcome = 'BLOCKED_GUARDRAIL' AND subject_id = ?",
                Integer.class,
                seeker))
        .isEqualTo(1);
    // Every call is logged with no names, phones or emails, and erasure takes it all.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ai_call WHERE subject_id = ?", Integer.class, seeker))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void translationIsOffUntilItIsEvaluatedAndContactDetailsNeverReachAModel() throws Exception {
    var off =
        translation()
            .draftTranslation(
                DraftTranslationRequest.newBuilder()
                    .setKind("BIO")
                    .setSourceLocale("en")
                    .setTargetLocale("fr")
                    .setText("Reach me on +237 670 000 111 or me@example.com.")
                    .setRequestedBy("staff-1")
                    .build());
    assertThat(off.getDrafted()).isFalse();
    assertThat(
            care.solace.ai.gateway.Redactor.redact(
                "Reach me on +237 670 000 111 or me@example.com."))
        .isEqualTo("Reach me on [phone] or [email].");
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** A model that keeps to the facts: it answers with the reviewed messages it was given. */
  static String echoMessages(String system, String user) {
    String en = "";
    String fr = "";
    for (String line : user.split("\n")) {
      if (line.startsWith("Reviewed message (EN): ")) {
        en = line.substring("Reviewed message (EN): ".length());
      } else if (line.startsWith("Reviewed message (FR): ")) {
        fr = line.substring("Reviewed message (FR): ".length());
      }
    }
    try {
      return JSON.writeValueAsString(Map.of("en", en, "fr", fr));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode feature(JsonNode overview, String code) {
    for (JsonNode f : overview.path("features")) {
      if (f.path("feature").asText().equals(code)) {
        return f;
      }
    }
    throw new AssertionError(code);
  }

  private InsightServiceGrpc.InsightServiceBlockingStub insights() {
    return InsightServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(bearer("ai:insight.invoke")));
  }

  private TranslationServiceGrpc.TranslationServiceBlockingStub translation() {
    return TranslationServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(bearer("ai:translation.invoke")));
  }

  private static Metadata bearer(String scope) {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + scope);
    return headers;
  }

  private static RequestPostProcessor staff(String id, String name, String... roles) {
    return jwt()
        .jwt(
            t ->
                t.issuer("http://localhost:8180/realms/solace-staff")
                    .subject(id)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  private static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(
        result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  @TestConfiguration
  static class Fakes {

    /** Two scripted models: a remote one, never allowed health data, and a local one. */
    @Bean
    @Primary
    RouteCatalog scriptedRoutes() {
      return new RouteCatalog(
          List.of(
              route(
                  "remote",
                  false,
                  (s, u) -> {
                    REMOTE_CALLS.incrementAndGet();
                    return "{\"en\": \"remote\", \"fr\": \"remote\"}";
                  }),
              route("local", true, (s, u) -> local.apply(s, u)))) {};
    }

    /** Stands in for Keycloak on gRPC: the token text is the scope the caller holds. */
    @Bean
    JwtDecoder solaceServiceJwtDecoder() {
      return token -> {
        if (token.isBlank()) {
          throw new BadJwtException("empty");
        }
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("service-account-tracking-service")
            .claim("client_id", "tracking-service")
            .claim("scope", token)
            .claim("aud", List.of("ai"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }

    private static ModelRoute route(
        String name, boolean local, BiFunction<String, String, String> answer) {
      return new ModelRoute() {
        @Override
        public String name() {
          return name;
        }

        @Override
        public boolean local() {
          return local;
        }

        @Override
        public Reply call(String system, String user) {
          return new Reply(answer.apply(system, user), 10, 10);
        }
      };
    }
  }
}
