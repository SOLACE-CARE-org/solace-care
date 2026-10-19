package care.solace.core.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.admin.FeatureFlags;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Join tokens and attendance (epic 7; D18, D36). Bookings are written straight into the table so
 * they can start minutes from now; the media server's webhooks are signed as LiveKit signs them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionIntegrationTest extends SpecialistFlowTest {

  private static final String SECRET = "solace-local-livekit-secret-0123456789";
  private static final AtomicInteger PHONES = new AtomicInteger(500);

  @Autowired BookingRecords bookings;
  @Autowired Sessions sessions;
  @MockitoSpyBean LiveKit liveKit;
  @MockitoSpyBean FeatureFlags flags;

  private Applicant specialist;
  private String seeker;
  private String seekerId;

  @BeforeAll
  void people() throws Exception {
    specialist =
        approvedSpecialist("+237696000301", "Room Keeper", staff("ops-s7", "R", "OPERATIONS"));
    seeker = seeker();
    seekerId = json(mvc.perform(get("/v1/me").headers(bearer(seeker)))).path("id").asText();
  }

  @Test
  void joiningOpensTenMinutesBeforeForTheTwoPeopleBooked() throws Exception {
    String later = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(30));
    mvc.perform(post("/v1/bookings/" + later + "/join").headers(bearer(seeker)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("session.not-open-yet"));

    String soon = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(5));
    JsonNode join =
        json(
            mvc.perform(post("/v1/bookings/" + soon + "/join").headers(bearer(seeker)))
                .andExpect(status().isOk()));
    assertThat(join.path("role").asText()).isEqualTo("SEEKER");
    assertThat(join.path("room").asText()).isEqualTo(soon);
    var claims = SignedJWT.parse(join.path("token").asText()).getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo("seeker");
    @SuppressWarnings("unchecked")
    Map<String, Object> video = (Map<String, Object>) claims.getClaim("video");
    assertThat(video.get("room")).isEqualTo(soon);
    assertThat(video.get("canPublishSources")).isEqualTo(List.of("microphone"));
    // Valid until 15 minutes after the end, and nothing that names the person.
    assertThat(claims.getExpirationTime().toInstant())
        .isAfter(Instant.now().plus(Duration.ofMinutes(5 + 50 + 14)));
    assertThat(claims.getClaims()).doesNotContainKey("name");

    String video2 = booking("CONFIRMED", "VIDEO", Duration.ofMinutes(5));
    JsonNode specialistJoin =
        json(
            mvc.perform(
                post("/v1/bookings/" + video2 + "/join").headers(bearer(specialist.token()))));
    assertThat(specialistJoin.path("role").asText()).isEqualTo("SPECIALIST");
    @SuppressWarnings("unchecked")
    Map<String, Object> grant =
        (Map<String, Object>)
            SignedJWT.parse(specialistJoin.path("token").asText())
                .getJWTClaimsSet()
                .getClaim("video");
    assertThat(grant.get("canPublishSources")).isEqualTo(List.of("microphone", "camera"));

    // Nobody else, and not before it is paid for.
    mvc.perform(post("/v1/bookings/" + soon + "/join").headers(bearer(seeker())))
        .andExpect(status().isNotFound());
    String unpaid = booking("PENDING_PAYMENT", "AUDIO", Duration.ofMinutes(5));
    mvc.perform(post("/v1/bookings/" + unpaid + "/join").headers(bearer(seeker)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("session.not-joinable"));
  }

  @Test
  void attendanceFromSignedWebhooksStartsAndCompletesTheSession() throws Exception {
    String id = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(-1));
    byte[] seekerJoined = event("participant_joined", id, "seeker");

    webhook(seekerJoined, null).andExpect(status().isUnauthorized());
    webhook(seekerJoined, sign(seekerJoined, "a-different-secret-0123456789-abcdef"))
        .andExpect(status().isUnauthorized());
    webhook(seekerJoined, sign("{}".getBytes(StandardCharsets.UTF_8), SECRET))
        .andExpect(status().isUnauthorized());

    webhook(seekerJoined, sign(seekerJoined, SECRET)).andExpect(status().isNoContent());
    webhook(seekerJoined, sign(seekerJoined, SECRET)).andExpect(status().isNoContent());
    assertThat(stateOf(id)).isEqualTo("CONFIRMED");

    byte[] specialistJoined = event("participant_joined", id, "specialist");
    webhook(specialistJoined, sign(specialistJoined, SECRET)).andExpect(status().isNoContent());
    assertThat(stateOf(id)).isEqualTo("IN_PROGRESS");

    byte[] finished = event("room_finished", id, null);
    webhook(finished, sign(finished, SECRET)).andExpect(status().isNoContent());
    assertThat(stateOf(id)).isEqualTo("COMPLETED");
  }

  @Test
  void attendanceSettlesNoShowsAndFlagsWhenNobodyCame() throws Exception {
    String onlySpecialist = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(-20));
    byte[] joined = event("participant_joined", onlySpecialist, "specialist");
    webhook(joined, sign(joined, SECRET)).andExpect(status().isNoContent());
    String onlySeeker = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(-20));
    byte[] seekerJoined = event("participant_joined", onlySeeker, "seeker");
    webhook(seekerJoined, sign(seekerJoined, SECRET)).andExpect(status().isNoContent());
    String nobody = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(-20));
    String overran = booking("IN_PROGRESS", "AUDIO", Duration.ofMinutes(-70));

    sessions.settleNow();

    assertThat(stateOf(onlySpecialist)).isEqualTo("NO_SHOW");
    assertThat(stateOf(onlySeeker)).isEqualTo("NO_SHOW");
    assertThat(stateOf(nobody)).isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM session_flag WHERE booking_id = ?::uuid", String.class, nobody))
        .isEqualTo("BOTH_ABSENT");
    assertThat(stateOf(overran)).isEqualTo("COMPLETED");
  }

  @Test
  void theScribeRecordsOnlyWhenBothConsentedAndStopsWhenEitherRefuses() throws Exception {
    String id = booking("CONFIRMED", "AUDIO", Duration.ofMinutes(-1));
    doReturn("EG_1").when(liveKit).startAudioEgress(anyString(), anyString(), any());

    // Off by default: nothing is recorded and no one is asked.
    mvc.perform(get("/v1/bookings/" + id + "/scribe").headers(bearer(seeker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.recording").value(false));

    doReturn(true).when(flags).enabled("sessionScribe");
    scribeConsent(seeker, true);
    scribeConsent(specialist.token(), true);
    mvc.perform(get("/v1/bookings/" + id + "/scribe").headers(bearer(specialist.token())))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.seekerConsented").value(true))
        .andExpect(jsonPath("$.specialistConsented").value(true));
    // Someone else's booking is not found.
    mvc.perform(get("/v1/bookings/" + id + "/scribe").headers(bearer(seeker())))
        .andExpect(status().isNotFound());

    joinBoth(id);
    verify(liveKit).startAudioEgress(eq(id), eq("scribe-audio/" + id + ".ogg"), any());
    mvc.perform(get("/v1/bookings/" + id + "/scribe").headers(bearer(seeker)))
        .andExpect(jsonPath("$.recording").value(true));

    // The seeker changes their mind during the session: the recording stops at once.
    mvc.perform(
            put("/v1/bookings/" + id + "/scribe")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"on\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refusedForSession").value(true))
        .andExpect(jsonPath("$.recording").value(false));
    verify(liveKit).stopEgress(eq("EG_1"), any());

    byte[] finished = event("room_finished", id, null);
    webhook(finished, sign(finished, SECRET)).andExpect(status().isNoContent());
    webhook(finished, sign(finished, SECRET)).andExpect(status().isNoContent());
    // The AI service hears the session ended and only deletes the audio.
    assertThat(firstEventOn("core.session", seekerId, id))
        .contains("\"type\":\"core.session.ended\"")
        .contains("\"scribe\":false")
        .contains("\"audioKey\":\"scribe-audio/" + id + ".ogg\"")
        .doesNotContain("Caller");
  }

  @Test
  void aSessionWithoutConsentIsNeverRecorded() throws Exception {
    String id = booking("CONFIRMED", "VIDEO", Duration.ofMinutes(-1));
    doReturn(true).when(flags).enabled("sessionScribe");
    String other = seeker();
    String otherId = json(mvc.perform(get("/v1/me").headers(bearer(other)))).path("id").asText();
    jdbc.update("UPDATE booking SET seeker_account_id = ?::uuid WHERE id = ?::uuid", otherId, id);
    joinBoth(id);
    verify(liveKit, never()).startAudioEgress(eq(id), anyString(), any());
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private void joinBoth(String id) throws Exception {
    for (String who : List.of("seeker", "specialist")) {
      byte[] joined = event("participant_joined", id, who);
      webhook(joined, sign(joined, SECRET)).andExpect(status().isNoContent());
    }
  }

  private void scribeConsent(String token, boolean granted) throws Exception {
    mvc.perform(
            put("/v1/me/consents")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choices\":[{\"purpose\":\"AI_SCRIBE\",\"granted\":" + granted + "}]}"))
        .andExpect(status().isOk());
  }

  private String seeker() throws Exception {
    String phone = "+2376703" + String.format("%05d", PHONES.incrementAndGet());
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

  /** A 50-minute booking starting {@code in} from now. */
  private String booking(String status, String modality, Duration in) {
    UUID id = UUID.randomUUID();
    Instant start = Instant.now().plus(in);
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price,
                             session_language, seeker_locale, starts_at, ends_at, status, source,
                             policy_version)
        VALUES (?, ?::uuid, ?::uuid, ?, 50, 15000, 'en', 'en', ?, ?, ?, 'SEARCH', 1)
        """,
        id,
        seekerId,
        specialist.id(),
        modality,
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))),
        status);
    return id.toString();
  }

  private String stateOf(String bookingId) {
    return bookings.find(UUID.fromString(bookingId)).orElseThrow().status();
  }

  private static byte[] event(String type, String room, String identity) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("event", type);
    body.put("id", "EV_" + UUID.randomUUID());
    body.put("room", Map.of("name", room));
    if (identity != null) {
      body.put("participant", Map.of("identity", identity));
    }
    body.put("createdAt", String.valueOf(Instant.now().getEpochSecond()));
    return JSON.writeValueAsBytes(body);
  }

  /** As LiveKit signs a webhook: a token from the API key carrying the body's SHA-256. */
  private static String sign(byte[] body, String secret) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("devkey")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim(
                "sha256",
                Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(body)))
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private ResultActions webhook(byte[] body, String token) throws Exception {
    var request =
        post("/v1/webhooks/livekit").contentType("application/webhook+json").content(body);
    if (token != null) {
      request.header("Authorization", token);
    }
    return mvc.perform(request);
  }
}
