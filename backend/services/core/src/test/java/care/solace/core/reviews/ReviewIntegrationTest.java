package care.solace.core.reviews;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Ratings and reviews (epic 8; D37) on completed bookings written straight into the table: rating
 * once, moderation with contact details masked, averages shown from three ratings, replies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewIntegrationTest extends SpecialistFlowTest {

  private static final AtomicInteger PHONES = new AtomicInteger(700);

  private final RequestPostProcessor support = staff("sup-r", "E. Nkeng", "SUPPORT");
  private Applicant specialist;
  private String seeker;
  private String seekerId;

  @BeforeAll
  void people() throws Exception {
    specialist =
        approvedSpecialist("+237696000401", "Star Keeper", staff("ops-r", "R", "OPERATIONS"));
    mvc.perform(
        post("/v1/me/specialist-application/bio-confirmation")
            .headers(bearer(specialist.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    seeker = seeker();
    seekerId = json(mvc.perform(get("/v1/me").headers(bearer(seeker)))).path("id").asText();
  }

  @Test
  void aCompletedSessionIsRatedOnceAndItsWordsWaitForModeration() throws Exception {
    String upcoming = booking("CONFIRMED", Duration.ofDays(2));
    rate(upcoming, "{\"stars\":5}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("review.not-rateable"));

    String done = booking("COMPLETED", Duration.ofDays(-1));
    mvc.perform(get("/v1/bookings/" + done).headers(bearer(seeker)))
        .andExpect(jsonPath("$.rated").value(false));
    rate(
            done,
            """
            {"stars":5,"tags":["LISTENED","PRACTICAL_TOOLS"],
             "text":"She listened. Call her on WhatsApp at 6 77 00 00 00 if the app is slow.",
             "privateNote":"The call dropped once."}
            """)
        .andExpect(status().isCreated());
    rate(done, "{\"stars\":4}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("review.already-rated"));
    mvc.perform(get("/v1/bookings/" + done).headers(bearer(seeker)))
        .andExpect(jsonPath("$.rated").value(true));

    JsonNode item = queued(done);
    assertThat(item.path("flags").get(0).asText()).isEqualTo("CONTACT_DETAILS");
    assertThat(item.path("seekerRef").asText()).startsWith("#");
    assertThat(item.toString()).doesNotContain("The call dropped once.").doesNotContain("Caller");
    String id = item.path("id").asText();

    // Not on the profile until published, and never with the number.
    mvc.perform(get("/v1/catalogue/specialists/" + specialist.id() + "/reviews"))
        .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).isEmpty());
    mvc.perform(
            post("/admin/v1/reviews/" + id + "/publication")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("review.has-contacts"));
    mvc.perform(post("/admin/v1/reviews/" + id + "/publication").with(staff("fin", "F", "FINANCE")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin/v1/reviews/" + id + "/publication")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maskContacts\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(get("/v1/catalogue/specialists/" + specialist.id() + "/reviews"))
        .andExpect(
            jsonPath("$[?(@.id == '%s')].text".formatted(id))
                .value("She listened. Call her on WhatsApp at ••• if the app is slow."));

    // The specialist replies once, never with contact details.
    reply(id, "{\"text\":\"Write to me at me@example.cm\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("review.reply-has-contacts"));
    reply(id, "{\"text\":\"Thank you. I'm glad it helped.\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("Thank you. I'm glad it helped."));
    reply(id, "{\"text\":\"Again\"}").andExpect(status().isConflict());
    mvc.perform(get("/v1/me/reviews").headers(bearer(specialist.token())))
        .andExpect(
            jsonPath("$.items[?(@.id == '%s')].reply".formatted(id))
                .value("Thank you. I'm glad it helped."));
  }

  @Test
  void anAverageShowsFromThreeRatingsAndFiltersSearch() throws Exception {
    rate(booking("COMPLETED", Duration.ofHours(-30)), "{\"stars\":4}")
        .andExpect(status().isCreated());
    // The specialist sees their own average at once.
    mvc.perform(get("/v1/me/reviews").headers(bearer(specialist.token())))
        .andExpect(jsonPath("$.rating.count").isNumber());
    rate(booking("COMPLETED", Duration.ofHours(-40)), "{\"stars\":3}")
        .andExpect(status().isCreated());
    rate(booking("COMPLETED", Duration.ofHours(-50)), "{\"stars\":5}")
        .andExpect(status().isCreated());

    JsonNode profile = json(mvc.perform(get("/v1/catalogue/specialists/" + specialist.id())));
    assertThat(profile.path("rating").path("count").asInt()).isGreaterThanOrEqualTo(3);
    double average = profile.path("rating").path("average").asDouble();

    mvc.perform(
            get("/v1/catalogue/specialists")
                .param("language", "en")
                .param("minRating", String.valueOf(average)))
        .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(specialist.id())).isNotEmpty());
    mvc.perform(get("/v1/catalogue/specialists").param("language", "en").param("minRating", "5"))
        .andExpect(
            average < 5
                ? jsonPath("$.items[?(@.id == '%s')]".formatted(specialist.id())).isEmpty()
                : status().isOk());
    mvc.perform(get("/v1/catalogue/specialists").param("language", "en").param("minRating", "6"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void staffCanHideAReviewWithAReason() throws Exception {
    String done = booking("COMPLETED", Duration.ofHours(-5));
    rate(done, "{\"stars\":2,\"text\":\"The session started late.\"}")
        .andExpect(status().isCreated());
    String id = queued(done).path("id").asText();
    mvc.perform(
            post("/admin/v1/reviews/" + id + "/hiding")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"About another platform\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HIDDEN"))
        .andExpect(jsonPath("$.hiddenReason").value("About another platform"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'reviews.hide' AND target_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private String seeker() throws Exception {
    String phone = "+2376704" + String.format("%05d", PHONES.incrementAndGet());
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Caller",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }

  private String booking(String status, Duration in) {
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
    return id.toString();
  }

  private ResultActions rate(String bookingId, String body) throws Exception {
    return mvc.perform(
        post("/v1/bookings/" + bookingId + "/rating")
            .headers(bearer(seeker))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions reply(String reviewId, String body) throws Exception {
    return mvc.perform(
        put("/v1/me/reviews/" + reviewId + "/reply")
            .headers(bearer(specialist.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private JsonNode queued(String bookingId) throws Exception {
    String reviewId =
        jdbc.queryForObject(
            "SELECT id::text FROM rating WHERE booking_id = ?::uuid", String.class, bookingId);
    for (JsonNode item : json(mvc.perform(get("/admin/v1/reviews").with(support)))) {
      if (item.path("id").asText().equals(reviewId)) {
        return item;
      }
    }
    throw new AssertionError("not in the queue: " + reviewId);
  }
}
