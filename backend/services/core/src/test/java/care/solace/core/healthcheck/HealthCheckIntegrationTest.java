package care.solace.core.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.bookings.HealthCheckShared;
import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** The health check end to end (Phase 2, epic 11; D55). */
class HealthCheckIntegrationTest extends SpecialistFlowTest {

  @Autowired ApplicationEventPublisher events;
  @Autowired TransactionTemplate transactions;

  private final RequestPostProcessor writer =
      staff("cr-writer", "Dr. A. Writer", "CLINICAL_REVIEWER");
  private final RequestPostProcessor second =
      staff("cr-second", "Dr. B. Second", "CLINICAL_REVIEWER");
  private final RequestPostProcessor editor = staff("ce-1", "C. Editor", "CONTENT_EDITOR");

  private static final Map<String, List<String>> WORRY =
      Map.of(
          "topics", List.of("worry", "stress"),
          "duration", List.of("one_to_six_months"),
          "impact", List.of("most_days"),
          "safety", List.of("no"),
          "phq9_1", List.of("several_days"),
          "phq9_2", List.of("not_at_all"),
          // GAD-2 of 2: the rest of GAD-7 is not asked.
          "gad7_1", List.of("more_than_half"),
          "gad7_2", List.of("not_at_all"),
          "medicine", List.of("no"));

  @Test
  void writtenByOneClinicianApprovedByAnotherAndTheLockedQuestionsNeverChange() throws Exception {
    publishedVersion();
    // A new draft: its author cannot approve it.
    String draft =
        json(mvc.perform(post("/admin/v1/health-check/versions").with(writer))
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    var v = json(mvc.perform(get("/admin/v1/health-check/versions/" + draft).with(writer)));
    assertThat(v.path("number").asText()).endsWith(".1");

    // Changing the safety question is refused.
    var questions = (com.fasterxml.jackson.databind.node.ArrayNode) v.path("questions").deepCopy();
    for (JsonNode q : questions) {
      if (q.path("code").asText().equals("safety")) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) q).put("textEn", "Are you okay?");
      }
    }
    mvc.perform(
            put("/admin/v1/health-check/versions/" + draft + "/questions")
                .with(writer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(questions.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("health-check.locked"));

    // Content editors look; they cannot write.
    mvc.perform(post("/admin/v1/health-check/versions/" + draft + "/submission").with(editor))
        .andExpect(status().isForbidden());

    mvc.perform(post("/admin/v1/health-check/versions/" + draft + "/submission").with(writer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    mvc.perform(
            post("/admin/v1/health-check/versions/" + draft + "/review")
                .with(writer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("health-check.same-person"));

    // The test run compares with the published version, on made-up answers only.
    var run =
        json(
            mvc.perform(
                post("/admin/v1/health-check/versions/" + draft + "/test-run").with(second)));
    assertThat(run).isNotEmpty();
    for (JsonNode line : run) {
      if (line.path("expected").isTextual()) {
        assertThat(line.path("specialty").asText()).isEqualTo(line.path("expected").asText());
        assertThat(line.path("published").asText()).isEqualTo(line.path("expected").asText());
      } else {
        assertThat(line.path("safetyStop").asBoolean()).isTrue();
      }
    }

    // Returned with comments, the draft goes back to its author.
    mvc.perform(
            post("/admin/v1/health-check/versions/" + draft + "/review")
                .with(second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"approve\":false,\"comments\":\"Check the wording of the medicine question.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.reviews[0].decision").value("RETURNED"));
  }

  @Test
  void aGuestAnswersGetsASuggestionAndKeepsItBySigningUp() throws Exception {
    publishedVersion();
    var form = json(mvc.perform(get("/v1/health-check").header("Accept-Language", "fr")));
    assertThat(form.path("questions").get(0).path("text").asText())
        .isEqualTo("Pour quoi souhaitez-vous de l'aide ?");

    var started = start(null);
    String id = started.path("id").asText();
    String token = started.path("token").asText();
    assertThat(token).isNotBlank();

    // Without the token the response is no one's.
    mvc.perform(answers(id, null, null, Map.of("topics", List.of("worry"))))
        .andExpect(status().isNotFound());

    var progress = json(mvc.perform(answers(id, token, null, WORRY)).andExpect(status().isOk()));
    assertThat(progress.path("status").asText()).isEqualTo("READY");
    // Answers are sealed: nothing readable is stored.
    byte[] sealed =
        jdbc.queryForObject("SELECT sealed FROM hc_response WHERE id = ?::uuid", byte[].class, id);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("worry");

    var result =
        json(
            mvc.perform(
                    post("/v1/health-check/responses/" + id + "/completion")
                        .header(HealthCheckController.TOKEN, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionLanguage\":\"en\",\"modality\":\"AUDIO\"}"))
                .andExpect(status().isOk()));
    assertThat(result.path("specialty").path("code").asText()).isEqualTo("clinical-psychology");
    assertThat(result.path("specialty").path("person").asText()).isEqualTo("Clinical psychologist");
    assertThat(result.path("alsoFit").path("code").asText()).isEqualTo("counselling");
    assertThat(result.path("reasons").get(0).asText())
        .isEqualTo("You said worry is affecting your daily life most days.");

    mvc.perform(
            get("/v1/health-check/responses/" + id + "/matches")
                .header(HealthCheckController.TOKEN, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    // Signing up keeps the answers; the guest token stops working.
    String seeker = seeker("+237670000901", true);
    mvc.perform(
            post("/v1/health-check/responses/" + id + "/claim")
                .headers(bearer(seeker))
                .header(HealthCheckController.TOKEN, token))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/health-check/responses/latest").headers(bearer(seeker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseId").value(id))
        .andExpect(jsonPath("$.stale").value(false));
    mvc.perform(
            get("/v1/health-check/responses/" + id + "/matches")
                .header(HealthCheckController.TOKEN, token))
        .andExpect(status().isNotFound());
  }

  @Test
  void aSeekerNeedsConsentAndTheSafetyAnswerAlertsTheOnCallSpecialist() throws Exception {
    publishedVersion();
    String noConsent = seeker("+237670000902", false);
    mvc.perform(
            post("/v1/health-check/responses")
                .headers(bearer(noConsent))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consentVersion\":\"v1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("health-check.consent-needed"));

    String seeker = seeker("+237670000903", true);
    String id = start(seeker).path("id").asText();
    var stop =
        json(
            mvc.perform(
                answers(
                    id,
                    null,
                    seeker,
                    Map.of("topics", List.of("low_mood"), "safety", List.of("yes_sometimes")))));
    assertThat(stop.path("status").asText()).isEqualTo("SAFETY_STOP");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crisis_alert WHERE source = 'HEALTH_CHECK' AND source_id = ?::uuid AND NOT guest",
                Integer.class,
                id))
        .isEqualTo(1);
    // Going on afterwards does not raise a second alert.
    var again =
        json(
            mvc.perform(
                answers(
                    id,
                    null,
                    seeker,
                    Map.of("topics", List.of("low_mood"), "safety", List.of("yes_sometimes")))));
    assertThat(again.path("status").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void aGuestLeavesANumberAfterTheSafetyAnswer() throws Exception {
    publishedVersion();
    var started = start(null);
    String id = started.path("id").asText();
    String token = started.path("token").asText();
    mvc.perform(
            post("/v1/health-check/responses/" + id + "/callback")
                .header(HealthCheckController.TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"+237670000904\"}"))
        .andExpect(status().isConflict());
    mvc.perform(answers(id, token, null, Map.of("safety", List.of("yes_often"))))
        .andExpect(jsonPath("$.status").value("SAFETY_STOP"));
    // A guest's alert waits for the number, so the responder has someone to call.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crisis_alert WHERE source_id = ?::uuid", Integer.class, id))
        .isZero();
    mvc.perform(
            post("/v1/health-check/responses/" + id + "/callback")
                .header(HealthCheckController.TOKEN, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"+237670000904\"}"))
        .andExpect(status().isAccepted());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crisis_alert WHERE source = 'HEALTH_CHECK' AND source_id = ?::uuid AND guest",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void theBookedSpecialistReadsSharedAnswersOnlyAndEveryReadIsAudited() throws Exception {
    publishedVersion();
    String seeker = seeker("+237670000905", true);
    UUID seekerId =
        UUID.fromString(
            json(mvc.perform(get("/v1/me").headers(bearer(seeker)))).path("id").asText());
    String id = start(seeker).path("id").asText();
    mvc.perform(answers(id, null, seeker, WORRY)).andExpect(status().isOk());
    mvc.perform(
            post("/v1/health-check/responses/" + id + "/completion")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    var specialist =
        approvedSpecialist("+237696000905", "Shared Reader", staff("ops-hc", "O", "OPERATIONS"));
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().plusSeconds(86_400);
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price, session_language,
                             seeker_locale, starts_at, ends_at, status, source, policy_version)
        VALUES (?, ?, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, 'CONFIRMED', 'HEALTH_CHECK', 1)
        """,
        booking,
        seekerId,
        specialist.id(),
        Timestamp.from(start),
        Timestamp.from(start.plusSeconds(3000)));

    mvc.perform(
            get("/v1/specialist/bookings/" + booking + "/health-check")
                .headers(bearer(specialist.token())))
        .andExpect(status().isNotFound());
    transactions.executeWithoutResult(
        s -> events.publishEvent(new HealthCheckShared(booking, UUID.fromString(id), seekerId)));
    var shared =
        json(
            mvc.perform(
                    get("/v1/specialist/bookings/" + booking + "/health-check")
                        .headers(bearer(specialist.token())))
                .andExpect(status().isOk()));
    assertThat(shared.path("answers").get(0).path("answers").toString())
        .contains("Worry or anxiety");
    assertThat(shared.path("scores").get(0).path("band").asText()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'health-check.shared.read' AND subject_id = ?",
                Integer.class,
                seekerId.toString()))
        .isEqualTo(2);
    // Not the seeker's own token, and not another specialist.
    mvc.perform(get("/v1/specialist/bookings/" + booking + "/health-check").headers(bearer(seeker)))
        .andExpect(status().isForbidden());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** The seeded v1 draft, approved by a second clinician and published, once per run. */
  private void publishedVersion() throws Exception {
    var list = json(mvc.perform(get("/admin/v1/health-check/versions").with(writer)));
    for (JsonNode v : list) {
      if (v.path("status").asText().equals("PUBLISHED")) {
        return;
      }
    }
    String id = null;
    for (JsonNode v : list) {
      if (v.path("status").asText().equals("DRAFT")) {
        id = v.path("id").asText();
      }
    }
    assertThat(id).isNotNull();
    mvc.perform(post("/admin/v1/health-check/versions/" + id + "/submission").with(writer))
        .andExpect(status().isOk());
    mvc.perform(
            post("/admin/v1/health-check/versions/" + id + "/review")
                .with(second)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true}"))
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/health-check/versions/" + id + "/publication").with(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  private JsonNode start(String seeker) throws Exception {
    var request =
        post("/v1/health-check/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"consentVersion\":\"v1\"}");
    if (seeker != null) {
      request.headers(bearer(seeker));
    }
    return json(mvc.perform(request).andExpect(status().isCreated()));
  }

  private org.springframework.test.web.servlet.RequestBuilder answers(
      String id, String token, String seeker, Map<String, List<String>> answers) throws Exception {
    var request =
        put("/v1/health-check/responses/" + id + "/answers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("answers", answers)));
    if (token != null) {
      request.header(HealthCheckController.TOKEN, token);
    }
    if (seeker != null) {
      request.headers(bearer(seeker));
    }
    return request;
  }

  private String seeker(String phone, boolean consent) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "HC",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    String id = json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
    if (consent) {
      jdbc.update(
          "INSERT INTO consent_record (account_id, purpose, granted, version, recorded_at) VALUES (?::uuid, 'HEALTH_CHECK', true, 'v1', now())",
          id);
    }
    return token;
  }
}
