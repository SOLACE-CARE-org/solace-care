package care.solace.ai.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.ai.gateway.ModelRoute;
import care.solace.ai.gateway.RouteCatalog;
import care.solace.contracts.core.v1.MetricRow;
import care.solace.contracts.core.v1.MetricsServiceGrpc;
import care.solace.contracts.core.v1.QueryMetricResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The admin copilot (Phase 4; D58) with a scripted model and Core's metrics stubbed: a catalogue
 * metric answers without a model, words need the copilot on, and an answer with a number not in the
 * table is thrown away.
 */
@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=ai-copilot",
      "spring.grpc.server.port=0",
      "spring.kafka.listener.auto-startup=false",
      "solace.grpc.client-registration=",
      "solace.ai.routes.ADMIN_COPILOT[0]=local"
    })
@AutoConfigureMockMvc
@Testcontainers
class CopilotTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:0.8.0-pg16")
              .asCompatibleSubstituteFor("postgres"));

  static final ObjectMapper JSON = new ObjectMapper();

  /** What the scripted model says when asked to word an answer. */
  static volatile String wording = "In all, 112 bookings came from tracking invites.";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MetricsServiceGrpc.MetricsServiceBlockingStub core;

  private final RequestPostProcessor superAdmin = staff("sa-1", "S. Admin", "SUPER_ADMIN");

  @BeforeEach
  void table() {
    when(core.queryMetric(any()))
        .thenReturn(
            QueryMetricResponse.newBuilder()
                .addColumns("Module")
                .addColumns("Invites shown")
                .addColumns("Bookings")
                .addRows(
                    MetricRow.newBuilder().addCells("PCOS & cycle").addCells("310").addCells("74"))
                .addRows(
                    MetricRow.newBuilder()
                        .addCells("Daily check-in")
                        .addCells("205")
                        .addCells("38"))
                .setSource("Source: tracking invites shown")
                .setFrom("2026-09-01T00:00:00Z")
                .setTo("2026-10-01T00:00:00Z")
                .build());
  }

  @Test
  void aCatalogueMetricIsAnsweredWithoutAModelAndOnlyForAnalyticsStaff() throws Exception {
    mvc.perform(
            post("/admin/v1/copilot/questions")
                .with(staff("sup", "Support", "SUPPORT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"INVITES_BY_MODULE\",\"period\":\"LAST_MONTH\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("Here are the numbers for last month."))
        .andExpect(jsonPath("$.aiWorded").value(false))
        .andExpect(jsonPath("$.rows[0][1]").value("310"));
    mvc.perform(
            post("/admin/v1/copilot/questions")
                .with(staff("cr", "Crisis", "CRISIS_RESPONDER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"INVITES_BY_MODULE\"}"))
        .andExpect(status().isForbidden());
    // Words need the copilot switched on.
    mvc.perform(
            put("/admin/v1/ai/features/ADMIN_COPILOT/switch")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"Off for this check\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/admin/v1/copilot/questions")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"How many bookings came from invites last month?\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("copilot.no-match"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'copilot.ask'", Integer.class))
        .isPositive();
  }

  @Test
  void aQuestionInWordsIsMappedAndAnsweredOnlyFromTheTable() throws Exception {
    goLive();
    String question =
        "{\"question\":\"How many bookings came from tracking recommendations last month, by module?\"}";
    mvc.perform(
            post("/admin/v1/copilot/questions")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(question))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metric").value("INVITES_BY_MODULE"))
        .andExpect(jsonPath("$.period").value("LAST_MONTH"))
        .andExpect(jsonPath("$.aiWorded").value(true))
        .andExpect(jsonPath("$.answer").value("In all, 112 bookings came from tracking invites."));

    // A number the table does not hold is thrown away; the plain sentence stands in.
    wording = "About 500 bookings came from invites.";
    mvc.perform(
            post("/admin/v1/copilot/questions")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(question))
        .andExpect(jsonPath("$.aiWorded").value(false))
        .andExpect(jsonPath("$.answer").value("Here are the numbers for last month."));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private void goLive() throws Exception {
    var overview = json(mvc.perform(get("/admin/v1/ai/features").with(superAdmin)));
    String draft = "";
    for (JsonNode f : overview.path("features")) {
      if (f.path("feature").asText().equals("ADMIN_COPILOT")) {
        draft = f.path("candidate").path("id").asText();
      }
    }
    var evaluation =
        json(mvc.perform(post("/admin/v1/ai/versions/" + draft + "/evaluation").with(superAdmin)));
    assertThat(evaluation.path("evaluation").path("passed").asBoolean())
        .as(evaluation.toString())
        .isTrue();
    mvc.perform(
            post("/admin/v1/ai/versions/" + draft + "/sign-off")
                .with(staff("cr-1", "Dr. Grace", "CLINICAL_REVIEWER")))
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/promotion").with(superAdmin))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin/v1/ai/features/ADMIN_COPILOT/switch")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Evaluated and signed off\"}"))
        .andExpect(status().isOk());
  }

  /** A scripted model: keyword routing for "choose", the set wording for "answer". */
  static String scripted(String system, String user) {
    if (user.startsWith("Task: answer")) {
      return wording;
    }
    String q = user.substring(user.indexOf("Question: ") + 10).toLowerCase(Locale.ROOT);
    String metric =
        q.contains("invit") || q.contains("recommendation")
            ? "INVITES_BY_MODULE"
            : q.contains("language") || q.contains("langue")
                ? "SESSIONS_BY_LANGUAGE"
                : q.contains("payment") || q.contains("paiement")
                    ? "PAYMENTS_BY_STATUS"
                    : q.contains("audio") || q.contains("vidéo")
                        ? "SESSIONS_BY_MODALITY"
                        : q.contains("note") || q.contains("rating")
                            ? "RATINGS"
                            : q.contains("annul") || q.contains("absence")
                                ? "BOOKINGS_BY_STATUS"
                                : "BOOKINGS_BY_SOURCE";
    String period =
        q.contains("last month") || q.contains("mois dernier") ? "LAST_MONTH" : "LAST_30_DAYS";
    return "{\"metric\": \"" + metric + "\", \"period\": \"" + period + "\"}";
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
    @Bean
    @Primary
    RouteCatalog scriptedRoutes() {
      ModelRoute local =
          new ModelRoute() {
            @Override
            public String name() {
              return "local";
            }

            @Override
            public boolean local() {
              return true;
            }

            @Override
            public Reply call(String system, String user) {
              return new Reply(scripted(system, user), 10, 10);
            }
          };
      return new RouteCatalog(List.of(local)) {};
    }
  }
}
