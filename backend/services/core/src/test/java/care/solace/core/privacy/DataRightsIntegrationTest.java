package care.solace.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Download a copy of my data, and Delete my account and data (epic 11; D40). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataRightsIntegrationTest extends SpecialistFlowTest {

  @Autowired DataRights rights;

  private Applicant specialist;

  @BeforeAll
  void specialist() throws Exception {
    specialist = approvedSpecialist("+237696000701", "Data Keeper", staff("ops-p", "R", "OPERATIONS"));
  }

  @Test
  void aSeekerGetsACopyOfEverythingCoreHoldsAndOnlyThey() throws Exception {
    String phone = "+237670800001";
    String token = seeker(phone, "Mireille");
    String id = id(token);
    UUID booking = booking(id, "COMPLETED", Duration.ofDays(-3));
    payment(booking, phone);
    rating(booking, id, "Kind and clear.");

    JsonNode requested =
        json(mvc.perform(post("/v1/me/data-exports").headers(bearer(token))));
    assertThat(requested.path("status").asText()).isEqualTo("PREPARING");
    // Asking again gives the same copy.
    assertThat(json(mvc.perform(post("/v1/me/data-exports").headers(bearer(token)))).path("id").asText())
        .isEqualTo(requested.path("id").asText());

    rights.prepareNow();
    JsonNode ready = json(mvc.perform(get("/v1/me/data-exports/latest").headers(bearer(token))));
    assertThat(ready.path("status").asText()).isEqualTo("READY");
    assertThat(ready.path("expiresAt").asText()).isNotEmpty();
    String exportId = ready.path("id").asText();

    String file =
        mvc.perform(get("/v1/me/data-exports/" + exportId + "/file").headers(bearer(token)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    JsonNode copy = JSON.readTree(file);
    assertThat(copy.path("account").path("phone").asText()).isEqualTo(phone);
    assertThat(copy.path("account").path("displayName").asText()).isEqualTo("Mireille");
    assertThat(copy.path("bookings").get(0).path("specialist").asText()).isEqualTo("Data Keeper");
    assertThat(copy.path("payments").path("payments").get(0).path("amount").asLong()).isEqualTo(15000);
    assertThat(copy.path("ratings").get(0).path("text").asText()).isEqualTo("Kind and clear.");
    assertThat(copy.path("consents").isArray()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'privacy.export.download' AND target_id = ?",
                Integer.class,
                exportId))
        .isEqualTo(1);

    // Nobody else can download it.
    String other = seeker("+237670800002", "Kofi");
    mvc.perform(get("/v1/me/data-exports/" + exportId + "/file").headers(bearer(other)))
        .andExpect(status().isNotFound());
  }

  @Test
  void erasureWaitsForUpcomingSessionsThenLeavesOnlyATombstone() throws Exception {
    String phone = "+237670800003";
    String token = seeker(phone, "Awa");
    String id = id(token);
    mvc.perform(
            put("/v1/me/devices")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\"ExponentPushToken[erase]\",\"platform\":\"IOS\",\"timeZone\":\"Africa/Douala\"}"))
        .andExpect(status().isNoContent());
    UUID past = booking(id, "COMPLETED", Duration.ofDays(-2));
    payment(past, phone);
    rating(past, id, "Helpful.");
    UUID upcoming = booking(id, "CONFIRMED", Duration.ofDays(4));

    erase(token)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("privacy.upcoming-sessions"));
    mvc.perform(
            post("/v1/me/erasure")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":false}"))
        .andExpect(status().isBadRequest());

    jdbc.update("UPDATE booking SET status = 'CANCELLED' WHERE id = ?", upcoming);
    erase(token).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("ERASED"));

    var account = jdbc.queryForMap("SELECT status, phone, email, keycloak_id FROM user_account WHERE id = ?::uuid", id);
    assertThat(account.get("status")).isEqualTo("ERASED");
    assertThat(account.get("phone")).isNull();
    assertThat(account.get("keycloak_id")).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM seeker_profile WHERE account_id = ?::uuid", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM push_device WHERE account_id = ?::uuid", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("SELECT phone FROM payment WHERE booking_id = ?", String.class, past)).isNull();
    assertThat(jdbc.queryForObject("SELECT text FROM rating WHERE booking_id = ?", String.class, past)).isNull();
    assertThat(jdbc.queryForObject("SELECT stars FROM rating WHERE booking_id = ?", Integer.class, past)).isEqualTo(4);
    // The booking stays as the accounting record.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE id = ?", Integer.class, past)).isEqualTo(1);
    // Tracking and AI are told, ids only.
    assertThat(firstEventOn("core.user", id, "core.user.erased"))
        .doesNotContain(phone)
        .doesNotContain("Awa");
    // The old token no longer reaches an account, and the number is free again.
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isForbidden());
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signup(phone, "Awa again")))
        .andExpect(status().isAccepted());
  }

  @Test
  void aSpecialistsErasureIsFiledForOperations() throws Exception {
    erase(specialist.token()).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("REQUESTED"));
    erase(specialist.token()).andExpect(status().isAccepted());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM data_request WHERE account_id = ?::uuid AND kind = 'ERASURE' AND status = 'REQUESTED'",
                Integer.class,
                specialist.id()))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM user_account WHERE id = ?::uuid", String.class, specialist.id()))
        .isEqualTo("ACTIVE");
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private org.springframework.test.web.servlet.ResultActions erase(String token) throws Exception {
    return mvc.perform(
        post("/v1/me/erasure")
            .headers(bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirm\":true}"));
  }

  private static String signup(String phone, String name) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "displayName", name,
            "phone", phone,
            "dateOfBirth", "1990-01-01",
            "termsVersion", "v1",
            "privacyNoticeVersion", "v1",
            "appLanguage", "en"));
  }

  private String seeker(String phone, String name) throws Exception {
    mvc.perform(post("/v1/signups").contentType(MediaType.APPLICATION_JSON).content(signup(phone, name)))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }

  private String id(String token) throws Exception {
    return json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
  }

  private UUID booking(String seekerId, String status, Duration in) {
    UUID id = UUID.randomUUID();
    Instant start = Instant.now().plus(in);
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version)
        VALUES (?, ?::uuid, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, ?, 'SEARCH', 1)
        """,
        id,
        seekerId,
        specialist.id(),
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))),
        status);
    return id;
  }

  private void payment(UUID booking, String phone) {
    jdbc.update(
        """
        INSERT INTO payment (id, booking_id, provider, method, phone, amount, fee_percent, status,
                             provider_ref, created_at, updated_at, completed_at)
        VALUES (?, ?, 'sandbox', 'MTN_MOMO', ?, 15000, 20, 'COMPLETE', ?, now(), now(), now())
        """,
        UUID.randomUUID(),
        booking,
        phone,
        "sbx_" + UUID.randomUUID());
  }

  private void rating(UUID booking, String seekerId, String text) {
    jdbc.update(
        """
        INSERT INTO rating (id, booking_id, specialist_id, subject_id, guest, session_at,
                            session_language, stars, text, status, created_at)
        VALUES (?, ?, ?::uuid, ?::uuid, false, now(), 'en', 4, ?, 'PUBLISHED', now())
        """,
        UUID.randomUUID(),
        booking,
        specialist.id(),
        seekerId,
        text);
  }
}
