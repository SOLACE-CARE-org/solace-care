package care.solace.core.bookings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.notifications.SignInCodes;
import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bookings (epic 5) against a specialist made through the real flows: Monday 09:00 to 12:00 in
 * Douala, 50-minute audio sessions at 15,000 FCFA. Each test books in its own week.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingIntegrationTest extends SpecialistFlowTest {

  private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");

  @MockitoSpyBean SignInCodes codes;
  @Autowired BookingRecords records;
  @Autowired Bookings bookings;

  private Applicant specialist;
  private LocalDate monday;

  @BeforeAll
  void specialist() throws Exception {
    specialist =
        approvedSpecialist("+237696000101", "Book Keeper", staff("ops-b", "R", "OPERATIONS"));
    mvc.perform(
        post("/v1/me/specialist-application/bio-confirmation")
            .headers(bearer(specialist.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    monday = LocalDate.now(DOUALA).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
  }

  @Test
  void aSeekerBooksPaysMovesAndCancels() throws Exception {
    String seeker = seeker("Ama", "+237670200001");
    LocalDate day = monday;
    JsonNode booking = json(book(seeker, hold(day, 10)).andExpect(status().isCreated()));
    String id = booking.path("id").asText();
    assertThat(booking.path("status").asText()).isEqualTo("PENDING_PAYMENT");
    assertThat(booking.path("price").asLong()).isEqualTo(15000);
    assertThat(booking.path("payBy").isMissingNode()).isFalse();
    assertThat(booking.path("specialist").path("fullName").asText()).isEqualTo("Book Keeper");
    assertThat(booking.has("seeker")).isFalse();
    // The time is taken for everyone else.
    hold(day, 10, status().isConflict());

    // Unpaid, cancelling refunds nothing.
    mvc.perform(get("/v1/bookings/" + id + "/cancellation").headers(bearer(seeker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundAmount").value(0));

    // Payments confirms it (epic 6); only once.
    assertThat(records.confirm(UUID.fromString(id), Instant.now())).isTrue();
    assertThat(records.confirm(UUID.fromString(id), Instant.now())).isFalse();
    assertThat(firstEventOn("core.booking", seekerId(seeker), "core.booking.confirmed"))
        .doesNotContain("Ama");

    // The specialist sees it with the seeker's chosen name, never a contact.
    mvc.perform(
            get("/v1/me/bookings").headers(bearer(specialist.token())).param("scope", "UPCOMING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '%s')].seeker.label".formatted(id)).value("Ama"));
    mvc.perform(get("/v1/me/bookings").headers(bearer(seeker)).param("scope", "UPCOMING"))
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$[0].reschedulesLeft").value(2))
        .andExpect(jsonPath("$[0].sessionNumber").value(1));

    // Moved to 11:00 at no charge; 10:00 is free again.
    mvc.perform(
            post("/v1/bookings/" + id + "/reschedule")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdId\":\"%s\"}".formatted(hold(day, 11))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value(at(day, 11)))
        .andExpect(jsonPath("$.reschedulesLeft").value(1));
    String ten = hold(day, 10);
    mvc.perform(delete(ten));

    // More than 24 hours ahead: a full refund (D33).
    mvc.perform(get("/v1/bookings/" + id + "/cancellation").headers(bearer(seeker)))
        .andExpect(jsonPath("$.refundAmount").value(15000))
        .andExpect(jsonPath("$.refundPercent").value(100));
    mvc.perform(
            post("/v1/bookings/" + id + "/cancellation")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"FEELING_BETTER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledBy").value("SEEKER"))
        .andExpect(jsonPath("$.refundAmount").value(15000));
    mvc.perform(
            post("/v1/bookings/" + id + "/cancellation")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("booking.not-cancellable"));
    mvc.perform(delete(hold(day, 11)));
    mvc.perform(get("/v1/me/bookings").headers(bearer(seeker)).param("scope", "PAST"))
        .andExpect(jsonPath("$[0].status").value("CANCELLED"));

    // Nobody else can see it.
    String other = seeker("Kofi", "+237670200002");
    mvc.perform(get("/v1/bookings/" + id).headers(bearer(other))).andExpect(status().isNotFound());
  }

  @Test
  void specialistsDoNotBookAndLanguagesMustBeOffered() throws Exception {
    LocalDate day = monday.plusWeeks(1);
    book(specialist.token(), hold(day, 9))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("booking.not-a-seeker"));
    String seeker = seeker("Efua", "+237670200003");
    mvc.perform(
            post("/v1/bookings")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"holdId\":\"%s\",\"sessionLanguage\":\"ewo\"}".formatted(hold(day, 10))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("booking.not-bookable"));
    book(seeker, UUID.randomUUID().toString())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("booking.hold-expired"));
  }

  @Test
  void unpaidBookingsExpireAndFreeTheTime() throws Exception {
    LocalDate day = monday.plusWeeks(2);
    String seeker = seeker("Yaw", "+237670200004");
    String id =
        json(book(seeker, hold(day, 9)).andExpect(status().isCreated())).path("id").asText();
    jdbc.update("UPDATE booking SET pay_by = now() - interval '1 minute' WHERE id = ?::uuid", id);
    bookings.expireUnpaid();
    assertThat(records.find(UUID.fromString(id)).orElseThrow().status()).isEqualTo("EXPIRED");
    mvc.perform(delete(hold(day, 9)));
    mvc.perform(get("/v1/me/bookings").headers(bearer(seeker)).param("scope", "UPCOMING"))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void aGuestBooksWithACodeAndKeepsTheBookingOnSigningUp() throws Exception {
    LocalDate day = monday.plusWeeks(3);
    String phone = "+237670200005";
    mvc.perform(
            post("/v1/guest/codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"%s\",\"appLanguage\":\"fr\"}".formatted(phone)))
        .andExpect(status().isAccepted());
    var code = ArgumentCaptor.forClass(String.class);
    verify(codes, atLeastOnce())
        .send(eq(SignInCodes.Channel.SMS), eq(phone), code.capture(), anyInt(), any());
    String hold = hold(day, 10);

    String wrong = code.getValue().equals("000000") ? "111111" : "000000";
    guestBook(hold, phone, wrong)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("guest.code-rejected"));
    JsonNode guest = json(guestBook(hold, phone, code.getValue()).andExpect(status().isCreated()));
    String token = guest.path("guestToken").asText();
    String id = guest.path("booking").path("id").asText();
    assertThat(guest.path("booking").path("status").asText()).isEqualTo("PENDING_PAYMENT");
    // The code works once.
    guestBook(hold(day, 11), phone, code.getValue()).andExpect(status().isBadRequest());

    mvc.perform(get("/v1/guest/booking").header("X-Guest-Token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.guestPhone").value("+237 6 •• •• •• 05"));
    mvc.perform(get("/v1/guest/booking").header("X-Guest-Token", "not-a-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("guest.token-invalid"));
    mvc.perform(
            get("/v1/me/bookings").headers(bearer(specialist.token())).param("scope", "UPCOMING"))
        .andExpect(jsonPath("$[?(@.id == '%s')].seeker.label".formatted(id)).value("Guest"))
        .andExpect(jsonPath("$[?(@.id == '%s')].seeker.guest".formatted(id)).value(true));

    // Signing up with the same phone claims it.
    String seeker = seeker("Abena", phone);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                mvc.perform(get("/v1/bookings/" + id).headers(bearer(seeker)))
                    .andExpect(status().isOk()));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private String seeker(String name, String phone) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", name,
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    // The app asks for /v1/me after signing in; that records the proven contact.
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }

  private String seekerId(String token) throws Exception {
    return json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
  }

  private static String at(LocalDate day, int hour) {
    return day.atTime(hour, 0).atZone(DOUALA).toInstant().toString();
  }

  private String hold(LocalDate day, int hour) throws Exception {
    return json(hold(day, hour, status().isCreated())).path("holdId").asText();
  }

  private ResultActions hold(
      LocalDate day, int hour, org.springframework.test.web.servlet.ResultMatcher expected)
      throws Exception {
    return mvc.perform(
            post("/v1/slot-holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"specialistId\":\"%s\",\"start\":\"%s\",\"modality\":\"AUDIO\"}"
                        .formatted(specialist.id(), at(day, hour))))
        .andExpect(expected);
  }

  private static org.springframework.test.web.servlet.RequestBuilder delete(String holdId) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
        "/v1/slot-holds/" + holdId);
  }

  private ResultActions book(String token, String holdId) throws Exception {
    return mvc.perform(
        post("/v1/bookings")
            .headers(bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holdId\":\"%s\",\"sessionLanguage\":\"en\"}".formatted(holdId)));
  }

  private ResultActions guestBook(String holdId, String phone, String code) throws Exception {
    return mvc.perform(
        post("/v1/guest/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"holdId":"%s","sessionLanguage":"en","phone":"%s","code":"%s","appLanguage":"fr"}
                """
                    .formatted(holdId, phone, code)));
  }
}
