package care.solace.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.MemberStackTest;
import care.solace.core.admin.FeatureFlags;
import care.solace.core.notifications.Delivery;
import care.solace.core.payments.PaymentReceived;
import care.solace.core.payments.RefundSent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Messages from real events through the logging senders (epic 9; D38): push in the member's
 * language with an inbox copy, discreet mode, guest SMS with a working booking link, and reminders
 * sent once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIntegrationTest extends MemberStackTest {

  private static final AtomicInteger PHONES = new AtomicInteger(800);

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationEventPublisher events;
  @Autowired TransactionTemplate transactions;
  @Autowired Messages messages;
  @MockitoSpyBean Delivery delivery;
  @MockitoSpyBean FeatureFlags flags;

  private String seeker;
  private String seekerId;
  private String specialistId;

  @BeforeAll
  void people() throws Exception {
    seeker = member("Ama");
    seekerId = id(seeker);
    specialistId = id(member("Kofi"));
    register(seeker, "ExponentPushToken[seeker-" + UUID.randomUUID() + "]");
  }

  @BeforeEach
  void forget() {
    clearInvocations(delivery);
  }

  @Test
  void aPaidBookingTellsTheSeekerAndTheSpecialist() throws Exception {
    UUID booking = booking(seekerId, null, Duration.ofDays(3));
    publish(new PaymentReceived(booking, UUID.fromString(seekerId), 15000));

    var text = ArgumentCaptor.forClass(String.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> verify(delivery, atLeastOnce()).push(anyList(), text.capture(), anyMap()));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                mvc.perform(get("/v1/me/notifications").headers(bearer(seeker)))
                    .andExpect(
                        jsonPath("$[?(@.bookingId == '%s')]".formatted(booking)).isNotEmpty()));
    mvc.perform(get("/v1/me/notifications").headers(bearer(seeker)))
        .andExpect(
            jsonPath(
                    "$[?(@.kind == 'PAYMENT_RECEIVED' && @.bookingId == '%s')].text"
                        .formatted(booking))
                .value("Payment received: 15,000 FCFA. Your receipt is in the app."));
    // The specialist's push names the format and the session language.
    assertThat(
            jdbc.queryForObject(
                "SELECT text FROM notification WHERE kind = 'NEW_BOOKING' AND booking_id = ?",
                String.class,
                booking))
        .startsWith("New session: ")
        .endsWith(" · audio · English.");
  }

  @Test
  void discreetModeHidesTheDetailsButTheInboxKeepsThem() throws Exception {
    mvc.perform(
            put("/v1/me/notification-settings")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionReminders\":true,\"discreet\":true}"))
        .andExpect(status().isOk());
    UUID booking = booking(seekerId, null, Duration.ofDays(4));
    publish(new RefundSent(booking, UUID.fromString(seekerId), 7500, "MTN_MOMO"));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                verify(delivery, atLeastOnce())
                    .push(anyList(), eq("Solace: you have a reminder"), anyMap()));
    mvc.perform(get("/v1/me/notifications").headers(bearer(seeker)))
        .andExpect(
            jsonPath("$[?(@.bookingId == '%s')].text".formatted(booking))
                .value("Solace: a refund of 7,500 FCFA has been sent to your MTN MoMo account."));
    mvc.perform(
        put("/v1/me/notification-settings")
            .headers(bearer(seeker))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sessionReminders\":true,\"discreet\":false}"));
  }

  @Test
  void aGuestGetsOneSmsInFrenchWithALinkThatOpensTheBooking() throws Exception {
    UUID guest = UUID.randomUUID();
    String phone = "+2376799" + String.format("%05d", PHONES.incrementAndGet());
    jdbc.update(
        "INSERT INTO guest_contact (id, phone, preferred_locale, verified_at) VALUES (?, ?, 'fr', now())",
        guest,
        phone);
    UUID booking = booking(null, guest, Duration.ofDays(5));
    publish(new PaymentReceived(booking, guest, 15000));

    var text = ArgumentCaptor.forClass(String.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(delivery).sms(eq(phone), text.capture()));
    assertThat(text.getValue())
        .startsWith("Solace : votre rendez-vous du ")
        .contains("https://slc.cm/g/");
    assertThat(delivery.smsLength(text.getValue())).isBetween(1, 160);
    String token = text.getValue().substring(text.getValue().indexOf("/g/") + 3);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                mvc.perform(get("/v1/guest/booking").header("X-Guest-Token", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(booking.toString())));
    // The SMS text, with its link, is never stored.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForList(
                            "SELECT coalesce(text, 'none') FROM notification"
                                + " WHERE booking_id = ? AND channel = 'SMS'",
                            String.class,
                            booking))
                    .containsExactly("none"));
  }

  @Test
  void joiningOpensIsSentOnceTenMinutesBefore() throws Exception {
    UUID booking = booking(seekerId, null, Duration.ofMinutes(10));
    messages.remindNow(Instant.now());
    messages.remindNow(Instant.now());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'JOIN_OPENS' AND booking_id = ?",
                Integer.class,
                booking))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE kind = 'SPECIALIST_SOON' AND booking_id = ?",
                Integer.class,
                booking))
        .isEqualTo(1);
    verify(delivery, never()).sms(anyString(), any());
  }

  @Test
  void theBriefIsAnnouncedAnHourBeforeOnlyWhileAiAssistIsOn() throws Exception {
    UUID booking = booking(seekerId, null, Duration.ofMinutes(60));
    messages.remindNow(Instant.now());
    String count =
        "SELECT count(*) FROM notification WHERE kind = 'BRIEF_READY' AND booking_id = ?";
    assertThat(jdbc.queryForObject(count, Integer.class, booking)).isZero();
    doReturn(true).when(flags).enabled("aiAssist");
    messages.remindNow(Instant.now());
    messages.remindNow(Instant.now());
    assertThat(jdbc.queryForObject(count, Integer.class, booking)).isEqualTo(1);
  }

  @Test
  void devicesNeedARealTimeZone() throws Exception {
    mvc.perform(
            put("/v1/me/devices")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"t\",\"platform\":\"IOS\",\"timeZone\":\"Mars/Base\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/v1/me/notifications/read").headers(bearer(seeker)))
        .andExpect(status().isNoContent());
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private void publish(Object event) {
    transactions.executeWithoutResult(s -> events.publishEvent(event));
  }

  private UUID booking(String seekerAccountId, UUID guestId, Duration in) {
    UUID id = UUID.randomUUID();
    Instant start = Instant.now().plus(in);
    jdbc.update(
        """
        INSERT INTO booking (id, seeker_account_id, guest_contact_id, specialist_id, modality,
                             duration_min, price, session_language, seeker_locale, starts_at,
                             ends_at, status, source, policy_version, pay_by)
        VALUES (?, ?::uuid, ?, ?::uuid, 'AUDIO', 50, 15000, 'en', 'en', ?, ?, 'CONFIRMED',
                'SEARCH', 1, ?)
        """,
        id,
        seekerAccountId,
        guestId,
        specialistId,
        Timestamp.from(start),
        Timestamp.from(start.plus(Duration.ofMinutes(50))),
        Timestamp.from(Instant.now().minus(Duration.ofDays(1))));
    return id;
  }

  private String member(String name) throws Exception {
    String phone = "+2376705" + String.format("%05d", PHONES.incrementAndGet());
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
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }

  private String id(String token) throws Exception {
    return JSON.readTree(
            mvc.perform(get("/v1/me").headers(bearer(token)))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("id")
        .asText();
  }

  private void register(String token, String device) throws Exception {
    mvc.perform(
            put("/v1/me/devices")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\"%s\",\"platform\":\"ANDROID\",\"timeZone\":\"Africa/Douala\"}"
                        .formatted(device)))
        .andExpect(status().isNoContent());
  }

  private static HttpHeaders bearer(String token) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    return h;
  }
}
