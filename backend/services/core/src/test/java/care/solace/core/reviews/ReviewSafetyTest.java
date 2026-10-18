package care.solace.core.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** The safety monitor in front of the moderation queue (D37, D39), with AI stood in for. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewSafetyTest extends SpecialistFlowTest {

  @MockitoBean ReviewModeration moderation;
  @Autowired ApplicationEventPublisher events;
  @Autowired TransactionTemplate transactions;

  private String seekerId;
  private String specialistId;

  @BeforeAll
  void people() throws Exception {
    specialistId =
        approvedSpecialist("+237696000601", "Safe Keeper", staff("ops-rs", "R", "OPERATIONS")).id();
    String phone = "+237670700001";
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
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    seekerId = json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
  }

  @Test
  void aFlaggedReviewNeverReachesTheQueueAndAClearOneDoes() {
    UUID flagged = review("I keep cutting myself.");
    UUID clear = review("Patient and practical.");
    UUID unchecked = review("The call dropped once.");
    when(moderation.flagged(eq(flagged), any(), any(), any())).thenReturn(Optional.of(true));
    when(moderation.flagged(eq(clear), any(), any(), any())).thenReturn(Optional.of(false));
    // AI could not answer: the review waits for a person (AI-4).
    when(moderation.flagged(eq(unchecked), any(), any(), any())).thenReturn(Optional.empty());

    for (UUID id : new UUID[] {flagged, clear, unchecked}) {
      transactions.executeWithoutResult(s -> events.publishEvent(new ReviewSubmitted(id)));
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(state(flagged)).isEqualTo("FLAGGED/FLAGGED");
              assertThat(state(clear)).isEqualTo("PENDING/CLEAR");
            });
    assertThat(state(unchecked)).isEqualTo("PENDING/NOT_CHECKED");
  }

  private String state(UUID review) {
    return jdbc.queryForObject(
        "SELECT status || '/' || safety FROM rating WHERE id = ?", String.class, review);
  }

  private UUID review(String text) {
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().minus(Duration.ofDays(1));
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version)
        VALUES (?, ?::uuid, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, 'COMPLETED', 'SEARCH', 1)
        """,
        booking,
        seekerId,
        specialistId,
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))));
    UUID review = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO rating (id, booking_id, specialist_id, subject_id, guest, session_at,
                            session_language, stars, text, status, created_at)
        VALUES (?, ?, ?::uuid, ?::uuid, false, ?, 'en', 3, ?, 'PENDING', now())
        """,
        review,
        booking,
        specialistId,
        seekerId,
        Timestamp.from(start),
        text);
    return review;
  }
}
