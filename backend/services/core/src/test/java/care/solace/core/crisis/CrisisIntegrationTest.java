package care.solace.core.crisis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.notifications.Delivery;
import care.solace.core.specialists.SpecialistFlowTest;
import care.solace.events.Events;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The crisis queue from a flagged review (epic 10; D11, D15, D39): one alert per decision, the
 * on-call SMS at once, escalation at 5 and 10 minutes, audited views, and closing with an outcome.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrisisIntegrationTest extends SpecialistFlowTest {

  @MockitoBean RiskDecisions risks;
  @MockitoSpyBean Delivery delivery;
  @Autowired CrisisAlerts alerts;

  private final RequestPostProcessor superAdmin = staff("sa-c", "S. Admin", "SUPER_ADMIN");
  private final RequestPostProcessor responder =
      staff("cr-1", "Dr. Fomba Grace", "CRISIS_RESPONDER");

  private String seekerId;
  private String specialistId;

  @BeforeAll
  void people() throws Exception {
    specialistId =
        approvedSpecialist("+237696000501", "Calm Keeper", staff("ops-c", "R", "OPERATIONS")).id();
    String phone = "+237670600001";
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Writer",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "fr"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    seekerId = json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();

    Instant now = Instant.now();
    mvc.perform(
            put("/admin/v1/crisis/rota")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    [{"staffId":"cr-1","name":"Dr. Fomba Grace","phone":"+237699000001","language":"fr",
                      "role":"PRIMARY","startsAt":"%1$s","endsAt":"%2$s"},
                     {"staffId":"cr-2","name":"Tchakounté Arnaud","phone":"+237699000002",
                      "role":"BACKUP","startsAt":"%1$s","endsAt":"%2$s"},
                     {"staffId":"sa-c","name":"S. Admin","phone":"+237699000003",
                      "role":"ESCALATION","startsAt":"%1$s","endsAt":"%2$s"}]
                    """
                        .formatted(now.minusSeconds(60), now.plus(Duration.ofDays(1)))))
        .andExpect(status().isOk());
  }

  @BeforeEach
  void forget() {
    clearInvocations(delivery);
  }

  @Test
  void aFlaggedReviewBecomesOneAlertTheResponderHandles() throws Exception {
    UUID decision = UUID.randomUUID();
    UUID review = review("Honnêtement je ne sais pas combien de temps je peux encore tenir.");
    when(risks.category(decision)).thenReturn(Optional.of("SELF_HARM"));
    var event = flagged(decision, review);
    alerts.on(event);
    alerts.on(event); // delivered twice: one alert

    String id =
        jdbc.queryForObject(
            "SELECT id::text FROM crisis_alert WHERE decision_id = ?", String.class, decision);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM crisis_alert WHERE decision_id = ?", Integer.class, decision))
        .isEqualTo(1);
    // The primary hears at once, in French, with no personal details.
    verify(delivery)
        .sms(
            eq("+237699000001"),
            org.mockito.ArgumentMatchers.startsWith(
                "Alerte de crise Solace : automutilation, levée à "));
    // The review left the moderation queue for good.
    assertThat(jdbc.queryForObject("SELECT status FROM rating WHERE id = ?", String.class, review))
        .isEqualTo("FLAGGED");

    mvc.perform(get("/admin/v1/crisis/alerts").with(responder).param("status", "OPEN"))
        .andExpect(jsonPath("$[?(@.id == '%s')].category".formatted(id)).value("SELF_HARM"))
        .andExpect(jsonPath("$[?(@.id == '%s')].text".formatted(id)).doesNotExist());
    mvc.perform(get("/admin/v1/crisis/summary").with(staff("sup", "S", "SUPPORT")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primary.name").value("Dr. Fomba Grace"));
    mvc.perform(get("/admin/v1/crisis/alerts/" + id).with(staff("fin", "F", "FINANCE")))
        .andExpect(status().isForbidden());

    mvc.perform(get("/admin/v1/crisis/alerts/" + id).with(responder))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.text")
                .value("Honnêtement je ne sais pas combien de temps je peux encore tenir."))
        .andExpect(jsonPath("$.contact").value("+237 6•• •• •• 01"))
        .andExpect(jsonPath("$.nextSession.specialistName").value("Calm Keeper"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'crisis.alert.read' AND target_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);

    mvc.perform(post("/admin/v1/crisis/alerts/" + id + "/acknowledgement").with(responder))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.acknowledgedBy").value("Dr. Fomba Grace"));
    mvc.perform(post("/admin/v1/crisis/alerts/" + id + "/acknowledgement").with(responder))
        .andExpect(status().isConflict());
    mvc.perform(post("/admin/v1/crisis/alerts/" + id + "/contact").with(responder))
        .andExpect(jsonPath("$.phone").value("+237670600001"));
    mvc.perform(
            post("/admin/v1/crisis/alerts/" + id + "/closure")
                .with(responder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"SAFE_FOR_NOW\",\"note\":\"Called; safe with family.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  void anUnacknowledgedAlertGoesToTheBackupThenTheAdmins() {
    UUID decision = UUID.randomUUID();
    when(risks.category(decision)).thenReturn(Optional.empty());
    alerts.on(flagged(decision, review("I want to die.")));
    Instant raised =
        jdbc.queryForObject(
                "SELECT raised_at FROM crisis_alert WHERE decision_id = ?",
                Timestamp.class,
                decision)
            .toInstant();
    // The category could not be read: the alert is raised anyway.
    assertThat(
            jdbc.queryForObject(
                "SELECT category FROM crisis_alert WHERE decision_id = ?", String.class, decision))
        .isEqualTo("UNKNOWN");

    alerts.escalateNow(raised.plus(Duration.ofMinutes(4)));
    verify(delivery, never()).sms(eq("+237699000002"), anyString());
    alerts.escalateNow(raised.plus(Duration.ofMinutes(6)));
    verify(delivery)
        .sms(
            eq("+237699000002"),
            eq(
                "Solace: a crisis alert has waited 5 min. You are the backup. Open the crisis queue now."));
    alerts.escalateNow(raised.plus(Duration.ofMinutes(11)));
    alerts.escalateNow(raised.plus(Duration.ofMinutes(12)));
    verify(delivery)
        .sms(
            eq("+237699000003"),
            eq(
                "Solace: a crisis alert is still not acknowledged after 5 min. Open the crisis queue now."));
  }

  @Test
  void onlyASuperAdminKeepsTheRota() throws Exception {
    mvc.perform(
            put("/admin/v1/crisis/rota")
                .with(responder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/v1/crisis/rota").with(responder)).andExpect(status().isOk());
    verify(delivery, never()).sms(any(), eq("never"));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  /** A completed booking with a written review, as the safety monitor would have seen it. */
  private UUID review(String text) {
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().minus(Duration.ofDays(1));
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version)
        VALUES (?, ?::uuid, ?::uuid, 'AUDIO', 50, 15000, 'fr', 'fr', ?, ?, 'COMPLETED', 'SEARCH', 1)
        """,
        booking,
        seekerId,
        specialistId,
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))));
    UUID next = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version)
        VALUES (?, ?::uuid, ?::uuid, 'VIDEO', 50, 15000, 'fr', 'fr', ?, ?, 'CONFIRMED', 'SEARCH', 1)
        """,
        next,
        seekerId,
        specialistId,
        Timestamp.from(Instant.now().plus(Duration.ofDays(9))),
        Timestamp.from(Instant.now().plus(Duration.ofDays(9)).plus(Duration.ofMinutes(50))));
    UUID review = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO rating (id, booking_id, specialist_id, subject_id, guest, session_at,
                            session_language, stars, text, status, created_at)
        VALUES (?, ?, ?::uuid, ?::uuid, false, ?, 'fr', 1, ?, 'PENDING', now())
        """,
        review,
        booking,
        specialistId,
        seekerId,
        Timestamp.from(start),
        text);
    return review;
  }

  private care.solace.events.EventEnvelope flagged(UUID decision, UUID review) {
    return Events.envelope(
        "ai.risk.flagged",
        1,
        seekerId,
        "fr",
        Map.of(
            "decisionId", decision.toString(),
            "source", "REVIEW",
            "sourceId", review.toString(),
            "classifierVersion", "phrases-1",
            "policyVersion", "safety-1"));
  }
}
