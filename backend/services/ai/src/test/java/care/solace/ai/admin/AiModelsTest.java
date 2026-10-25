package care.solace.ai.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
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
 * AI models and routes as rows (D61): the configured model becomes the first row, a new model is
 * active only with its key, and a changed route goes live only after an evaluation on it. And the
 * reviewed lists: safety phrases go live only once the safety set passes on them, signed off by a
 * second clinician.
 */
@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=ai-models",
      "spring.grpc.server.port=0",
      "spring.kafka.listener.auto-startup=false",
      "solace.grpc.client-registration=",
      "solace.ai.models[0].name=local-a",
      "solace.ai.models[0].url=http://127.0.0.1:1/v1",
      "solace.ai.models[0].model=llama",
      "solace.ai.models[0].local=true",
      "solace.ai.routes.TRANSLATION[0]=local-a"
    })
@AutoConfigureMockMvc
@Testcontainers
class AiModelsTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:0.8.0-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired MockMvc mvc;
  @Autowired ModelGateway gateway;

  private final RequestPostProcessor superAdmin = staff("sa-9", "S. Admin", "SUPER_ADMIN");
  private final RequestPostProcessor ops = staff("op-9", "O. Ops", "OPERATIONS");
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  @Test
  void theConfiguredModelIsTheFirstRowAndANewOneNeedsItsKey() throws Exception {
    mvc.perform(get("/admin/v1/ai-models").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name == 'local-a')].status").value("ACTIVE"))
        .andExpect(jsonPath("$[?(@.name == 'local-a')].keyFound").value(true));

    mvc.perform(
            post("/admin/v1/ai-models")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("remote-b", "https://models.example.com/v1", false)))
        .andExpect(status().isForbidden());
    // A model Solace does not host must use https.
    mvc.perform(
            post("/admin/v1/ai-models")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("remote-c", "http://models.example.com/v1", false)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ai-model.invalid"));
    mvc.perform(
            post("/admin/v1/ai-models")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("remote-b", "https://models.example.com/v1", false)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.keyFound").value(false));
    mvc.perform(
            put("/admin/v1/ai-models/remote-b")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":1,"url":"https://models.example.com/v1","model":"m","local":false,
                     "secretRef":"remote-b","status":"ACTIVE","priority":1,"reason":"Try it"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ai-model.not-ready"));
  }

  @Test
  void aChangedRouteGoesLiveOnlyAfterItsEvaluation() throws Exception {
    // The configured route became the first live route.
    assertThat(gateway.routeNames(Feature.TRANSLATION)).containsExactly("local-a");
    // A route may only name active chat models.
    route("COMPANION", "[\"nobody\"]").andExpect(status().isBadRequest());
    route("COMPANION", "[\"local-a\"]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pending[0]").value("local-a"))
        .andExpect(jsonPath("$.pendingPassed").value(false));
    // A model a route uses cannot be disabled.
    mvc.perform(
            put("/admin/v1/ai-models/local-a")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":1,"url":"http://127.0.0.1:1/v1","model":"llama","local":true,
                     "secretRef":"config","status":"DISABLED","priority":1,"reason":"Off"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ai-model.in-route"));
    // Not evaluated: the pending route stays pending; the live one is unchanged.
    promote("COMPANION")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ai-route.not-evaluated"));
    assertThat(gateway.routeNames(Feature.COMPANION)).isEmpty();

    // Taking every model off a feature needs no evaluation: it only turns the feature off.
    route("TRANSLATION", "[]").andExpect(status().isOk());
    promote("TRANSLATION").andExpect(status().isOk()).andExpect(jsonPath("$.live").isEmpty());
    assertThat(gateway.routeNames(Feature.TRANSLATION)).isEmpty();

    mvc.perform(
            put("/admin/v1/ai-features/COMPANION/budget")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tokens\":250000,\"reason\":\"Pilot\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.monthlyBudget").value(250000));
  }

  @Test
  void safetyPhrasesGoLiveOnlyWhenTheSafetySetPassesAndASecondClinicianSignsOff() throws Exception {
    var drafter = staff("cr-a", "Dr. Ade", "CLINICAL_REVIEWER");
    var approver = staff("cr-b", "Dr. Bih", "CLINICAL_REVIEWER");
    mvc.perform(get("/admin/v1/lists").with(ops))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.code == 'ai.safety.phrases')].approvers[0]")
                .value("CLINICAL_REVIEWER"));
    var phrases =
        JSON.readTree(
                mvc.perform(get("/admin/v1/lists/ai.safety.phrases").with(ops))
                    .andReturn()
                    .getResponse()
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .path("inForce");

    // No safety set yet: no phrases can pass.
    int first = draft("ai.safety.phrases", phrases, drafter).path("version").asInt();
    mvc.perform(
            post("/admin/v1/lists/ai.safety.phrases/drafts/" + first + "/sign-off").with(approver))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("list.check-failed"));

    // The clinicians' safety set, signed off by a second clinician.
    var set =
        JSON.readTree(
            """
            {"cases":[
              {"id":"en-1","locale":"en","text":"I want to end my life","crisis":true},
              {"id":"fr-1","locale":"fr","text":"je veux mettre fin a mes jours","crisis":true},
              {"id":"en-2","locale":"en","text":"I slept badly","crisis":false}]}
            """);
    int setVersion = draft("ai.eval.safety-monitor", set, drafter).path("version").asInt();
    mvc.perform(
            post("/admin/v1/lists/ai.eval.safety-monitor/drafts/" + setVersion + "/sign-off")
                .with(drafter))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("list.same-person"));
    mvc.perform(
            post("/admin/v1/lists/ai.eval.safety-monitor/drafts/" + setVersion + "/sign-off")
                .with(approver))
        .andExpect(status().isOk());

    mvc.perform(
            post("/admin/v1/lists/ai.safety.phrases/drafts/" + first + "/sign-off").with(approver))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVE"))
        .andExpect(
            jsonPath("$.checkResult").value(org.hamcrest.Matchers.startsWith("recall EN 100")));

    // The guardrail list always keeps "triage".
    mvc.perform(
            post("/admin/v1/lists/ai.guardrails.clinical/drafts")
                .with(drafter)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[\"\\bdiagnos\"],\"note\":\"Shorter\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("list.invalid"));
  }

  private com.fasterxml.jackson.databind.JsonNode draft(
      String code, com.fasterxml.jackson.databind.JsonNode entries, RequestPostProcessor who)
      throws Exception {
    return JSON.readTree(
        mvc.perform(
                post("/admin/v1/lists/" + code + "/drafts")
                    .with(who)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(Map.of("entries", entries, "note", "Clinicians"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  private ResultActions route(String feature, String models) throws Exception {
    return mvc.perform(
        put("/admin/v1/ai-routes/" + feature)
            .with(superAdmin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"models\":%s,\"reason\":\"Test\"}".formatted(models)));
  }

  private ResultActions promote(String feature) throws Exception {
    return mvc.perform(
        post("/admin/v1/ai-routes/" + feature + "/promotion")
            .with(superAdmin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Go\"}"));
  }

  private static String draft(String name, String url, boolean local) {
    return """
        {"name":"%s","kind":"CHAT","url":"%s","model":"m","local":%s,"secretRef":"%s",
         "priority":1,"reason":"New vendor"}
        """
        .formatted(name, url, local, name);
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

  @TestConfiguration
  static class Fakes {
    @Bean
    JwtDecoder solaceServiceJwtDecoder() {
      return token -> {
        if (token.isBlank()) {
          throw new BadJwtException("empty");
        }
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("service")
            .claim("scope", token)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
      };
    }
  }
}
