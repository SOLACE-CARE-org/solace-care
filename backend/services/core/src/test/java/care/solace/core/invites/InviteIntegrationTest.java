package care.solace.core.invites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.contracts.core.v1.BookingAccessServiceGrpc;
import care.solace.contracts.core.v1.CheckLiveBookingRequest;
import care.solace.contracts.core.v1.GetAgeRequest;
import care.solace.contracts.core.v1.ListSpecialtiesRequest;
import care.solace.contracts.core.v1.ProfileServiceGrpc;
import care.solace.contracts.core.v1.TaxonomyServiceGrpc;
import care.solace.core.specialists.SpecialistFlowTest;
import care.solace.events.Events;
import com.fasterxml.jackson.databind.JsonNode;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Tracking to care, Core's side (Phase 2, epics 6 and 7; D52). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "spring.grpc.server.inprocess.name=core-invites")
class InviteIntegrationTest extends SpecialistFlowTest {

  @Autowired Invites invites;

  private final ManagedChannel channel = InProcessChannelBuilder.forName("core-invites").build();

  @AfterAll
  void close() {
    channel.shutdownNow();
  }

  @Test
  void anInviteFromTrackingIsTheSeekersAndABookingCanAnswerIt() throws Exception {
    String token = seeker("+237670000477");
    UUID seeker =
        UUID.fromString(
            json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText());
    UUID recommendation = UUID.randomUUID();
    var event =
        Events.envelope(
            "tracking.recommendation.created",
            1,
            seeker.toString(),
            null,
            new Invites.Created(
                recommendation,
                "checkin.low_mood",
                "daily_checkin",
                "INVITE",
                List.of("counselling")));
    invites.on(event);
    invites.on(event);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tracking_invite WHERE recommendation_id = ?",
                Integer.class,
                recommendation))
        .isEqualTo(1);

    mvc.perform(get("/v1/me/invites/" + recommendation + "/specialists").headers(bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.specialties[0].code").value("counselling"))
        .andExpect(jsonPath("$.specialties[0].label").value("Counselling"))
        .andExpect(jsonPath("$.specialists").isArray());
    // Someone else's invite is not found.
    mvc.perform(
            get("/v1/me/invites/" + recommendation + "/specialists")
                .headers(bearer(seeker("+237670000478"))))
        .andExpect(status().isNotFound());

    // A booking cannot claim an invite that is not open.
    mvc.perform(
            post("/v1/bookings")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"holdId\":\""
                        + UUID.randomUUID()
                        + "\",\"sessionLanguage\":\"en\",\"source\":\"TRACKING_RECOMMENDATION\","
                        + "\"recommendationId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest());

    invites.on(
        Events.envelope(
            "tracking.recommendation.resolved",
            1,
            seeker.toString(),
            null,
            new Invites.Resolved(recommendation, "checkin.low_mood", "DISMISSED")));
    assertThat(invites.openFor(seeker, recommendation)).isFalse();
  }

  @Test
  void trackingAsksCoreOverGrpcWithItsOwnScopes() throws Exception {
    String service = serviceToken();
    var taxonomy = TaxonomyServiceGrpc.newBlockingStub(channel).withInterceptors(bearerOf(service));
    assertThat(
            taxonomy
                .listSpecialties(ListSpecialtiesRequest.getDefaultInstance())
                .getSpecialtiesList())
        .anyMatch(s -> s.getKey().equals("gynaecology") && !s.getActive());

    String token = seeker("+237670000479");
    UUID seeker =
        UUID.fromString(
            json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText());
    var profile = ProfileServiceGrpc.newBlockingStub(channel).withInterceptors(bearerOf(service));
    assertThat(
            profile
                .getAge(GetAgeRequest.newBuilder().setSeekerSubjectId(seeker.toString()).build())
                .getAgeYears())
        .isGreaterThanOrEqualTo(18);

    var access =
        BookingAccessServiceGrpc.newBlockingStub(channel).withInterceptors(bearerOf(service));
    var specialist =
        approvedSpecialist("+237696000277", "Share Reader", staff("ops-i", "O", "OPERATIONS"));
    var request =
        CheckLiveBookingRequest.newBuilder()
            .setSeekerSubjectId(seeker.toString())
            .setSpecialistSubjectId(specialist.id())
            .build();
    assertThat(access.checkLiveBooking(request).getLive()).isFalse();
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().plusSeconds(86_400);
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, specialist_id, modality, duration_min, price, session_language,
                             seeker_locale, starts_at, ends_at, status, source, policy_version)
        VALUES (?, ?::uuid, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, 'CONFIRMED', 'SEARCH', 1)
        """,
        booking,
        seeker.toString(),
        specialist.id(),
        Timestamp.from(start),
        Timestamp.from(start.plusSeconds(3000)));
    var live = access.checkLiveBooking(request);
    assertThat(live.getLive()).isTrue();
    assertThat(live.getBookingId()).isEqualTo(booking.toString());

    // Without a token, nothing.
    assertThatThrownBy(
            () ->
                TaxonomyServiceGrpc.newBlockingStub(channel)
                    .listSpecialties(ListSpecialtiesRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e ->
                assertThat(e.getStatus().getCode())
                    .isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED));
  }

  private String seeker(String phone) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Inv",
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

  /** Tracking's client-credentials token from the staff realm, as in the cluster. */
  private static String serviceToken() throws Exception {
    String form =
        "grant_type=client_credentials&client_id=tracking-service&client_secret="
            + URLEncoder.encode("tracking-service-dev-secret", StandardCharsets.UTF_8);
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            KEYCLOAK.getAuthServerUrl().replaceAll("/$", "")
                                + "/realms/solace-staff/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    JsonNode body = JSON.readTree(response.body());
    return body.path("access_token").asText();
  }

  private static io.grpc.ClientInterceptor bearerOf(String token) {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    return MetadataUtils.newAttachHeadersInterceptor(headers);
  }
}
