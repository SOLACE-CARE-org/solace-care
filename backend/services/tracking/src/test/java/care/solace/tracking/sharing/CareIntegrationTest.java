package care.solace.tracking.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.events.Events;
import care.solace.tracking.TrackingStackTest;
import care.solace.tracking.nudges.Nudges;
import care.solace.tracking.rules.BookingOutcomes;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** Sharing, nudges and booking outcomes (Phase 2, epics 6 to 8; D52). */
@TestPropertySource(
    properties = {
      "solace.tracking.nudges.every=PT24H",
      "spring.grpc.server.inprocess.name=tracking-care"
    })
class CareIntegrationTest extends TrackingStackTest {

  private static final String YESTERDAY = LocalDate.now().minusDays(1).toString();

  @MockitoBean LiveBookings bookings;
  @Autowired Nudges nudges;
  @Autowired BookingOutcomes outcomes;

  @Test
  void aSpecialistReadsOnlyWhatWasSharedAfterTheGrantAndOnlyWhileABookingIsLive() throws Exception {
    UUID seeker = UUID.randomUUID();
    UUID specialist = UUID.randomUUID();
    consent(seeker);
    push(seeker, entry("mood", 2, Instant.now().minusSeconds(3600)));

    // No live booking, no grant.
    when(bookings.live(seeker, specialist)).thenReturn(false);
    grant(seeker, specialist, false).andExpect(status().isForbidden());

    when(bookings.live(seeker, specialist)).thenReturn(true);
    String grantId =
        json(grant(seeker, specialist, false).andExpect(status().isCreated())).path("id").asText();
    UUID after = UUID.randomUUID();
    push(seeker, entry("mood", 4, Instant.now()));
    push(seeker, entry(after, "note", "private words", Instant.now()));

    mvc.perform(get("/v1/tracking/patients/" + seeker + "/entries").with(specialist(specialist)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.includesNotes").value(false))
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].element").value("mood"))
        .andExpect(jsonPath("$.entries[0].value").value(4));
    assertThat(audits(seeker, "ALLOWED")).isEqualTo(1);

    // Notes only with their own yes; the window restarts from the new grant.
    Thread.sleep(20);
    grant(seeker, specialist, true).andExpect(status().isCreated());
    Thread.sleep(20);
    push(seeker, entry(UUID.randomUUID(), "note", "shared words", Instant.now()));
    mvc.perform(get("/v1/tracking/patients/" + seeker + "/entries").with(specialist(specialist)))
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].value").value("shared words"));

    // Booking over: denied and audited.
    when(bookings.live(seeker, specialist)).thenReturn(false);
    mvc.perform(get("/v1/tracking/patients/" + seeker + "/entries").with(specialist(specialist)))
        .andExpect(status().isForbidden());
    assertThat(audits(seeker, "DENIED")).isEqualTo(1);

    // Another specialist, or a seeker token, gets nothing.
    when(bookings.live(any(), any())).thenReturn(true);
    mvc.perform(
            get("/v1/tracking/patients/" + seeker + "/entries").with(specialist(UUID.randomUUID())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/v1/tracking/patients/" + seeker + "/entries").with(seeker(specialist)))
        .andExpect(status().isForbidden());

    // Revoked: the old grant is already gone, the active one goes now.
    var grants = json(mvc.perform(get("/v1/tracking/shares").with(seeker(seeker))));
    assertThat(grants).hasSize(2);
    mvc.perform(delete("/v1/tracking/shares/" + grantId).with(seeker(seeker)))
        .andExpect(status().isNotFound());
    String active = grants.get(0).path("id").asText();
    mvc.perform(delete("/v1/tracking/shares/" + active).with(seeker(seeker)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/tracking/patients/" + seeker + "/entries").with(specialist(specialist)))
        .andExpect(status().isForbidden());
  }

  @Test
  void oneTrackingPushADayAndAnInviteTakesIt() {
    UUID seeker = UUID.randomUUID();
    consent(seeker);
    enrol(seeker);
    assertThat(nudges.invite(seeker)).isTrue();
    // The day's push is taken.
    assertThat(nudges.sendDueReminders()).isZero();
    assertThat(nudges.invite(seeker)).isFalse();
    assertThat(requested(seeker)).isEqualTo(1);
  }

  @Test
  void theReminderGoesOutAtTheEnrolmentTimeUnlessAlreadyLogged() throws Exception {
    UUID seeker = UUID.randomUUID();
    consent(seeker);
    enrol(seeker);
    assertThat(nudges.sendDueReminders()).isGreaterThanOrEqualTo(1);
    assertThat(requested(seeker)).isEqualTo(1);

    UUID logged = UUID.randomUUID();
    consent(logged);
    enrol(logged);
    push(logged, entry("mood", 3, Instant.now()));
    nudges.sendDueReminders();
    assertThat(requested(logged)).isZero();
  }

  @Test
  void nudgesStopAfterFiveIgnoredDaysAndWhileFlagged() throws Exception {
    UUID seeker = UUID.randomUUID();
    consent(seeker);
    for (int d = 1; d <= 5; d++) {
      jdbc.update(
          "INSERT INTO nudge (user_id, local_day, kind, sent_at) VALUES (?, ?, 'REMINDER', ?)",
          seeker,
          LocalDate.now().minusDays(d),
          Timestamp.from(Instant.now().minus(d, ChronoUnit.DAYS)));
    }
    assertThat(nudges.invite(seeker)).isFalse();
    // Logging again starts the count over.
    push(seeker, entry("mood", 3, Instant.now().minusSeconds(60)));
    assertThat(nudges.invite(seeker)).isTrue();

    UUID flagged = UUID.randomUUID();
    nudges.pause(flagged, Instant.now().plus(14, ChronoUnit.DAYS));
    assertThat(nudges.invite(flagged)).isFalse();
  }

  @Test
  void aBookingFromAnInviteResolvesTheRecommendation() {
    UUID seeker = UUID.randomUUID();
    UUID module = enrol(seeker);
    UUID rule = UUID.randomUUID();
    UUID version =
        jdbc.queryForObject(
            "SELECT module_version_id FROM enrolment WHERE user_id = ?", UUID.class, seeker);
    jdbc.update(
        """
        INSERT INTO rule (id, module_version_id, code, conditions_json, outcome, message_en, message_fr,
                          cooldown_days, position)
        VALUES (?, ?, 'test.rule', '[]', 'INVITE', 'Message', 'Message', 30, 0)
        """,
        rule,
        version);
    UUID recommendation = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO recommendation (id, user_id, rule_id, rule_code, module_id, evidence_json, outcome,
                                    status, created_at, next_eligible_at)
        VALUES (?, ?, ?, 'test.rule', ?, '{}', 'INVITE', 'SEEN', now(), now() + interval '30 days')
        """,
        recommendation,
        seeker,
        rule,
        module);
    UUID booking = UUID.randomUUID();
    var event =
        Events.envelope(
            "core.booking.confirmed",
            1,
            seeker.toString(),
            null,
            Map.of(
                "bookingId", booking.toString(),
                "specialistId", UUID.randomUUID().toString(),
                "source", "TRACKING_RECOMMENDATION",
                "guest", false,
                "recommendationId", recommendation.toString()));
    outcomes.on(event);
    outcomes.on(event);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM recommendation WHERE id = ?", String.class, recommendation))
        .isEqualTo("BOOKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?"
                    + " AND serialized_event LIKE '%BOOKED%'",
                Integer.class, "%" + recommendation + "%"))
        .isEqualTo(1);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** Enrols the person in a fresh published module with a reminder due now in Douala. */
  private UUID enrol(UUID user) {
    UUID module = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO module (id, code, accent_colour, created_by, created_at) VALUES (?, ?, 'teal', 'test', now())",
        module,
        "m_" + module.toString().replace("-", "").substring(0, 12));
    jdbc.update(
        """
        INSERT INTO module_version (id, module_id, major, minor, status, drafted_by, drafted_by_name, created_at)
        VALUES (?, ?, 1, 0, 'PUBLISHED', 'test', 'Test', now())
        """,
        version,
        module);
    LocalTime due = LocalTime.now(ZoneId.of("Africa/Douala")).truncatedTo(ChronoUnit.MINUTES);
    jdbc.update(
        """
        INSERT INTO enrolment (user_id, module_id, module_version_id, enrolled_at, reminder_time)
        VALUES (?, ?, ?, now(), ?)
        """,
        user,
        module,
        version,
        due);
    return module;
  }

  private int requested(UUID user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM event_publication WHERE serialized_event LIKE '%tracking.nudge.requested%'"
            + " AND serialized_event LIKE ?",
        Integer.class, "%" + user + "%");
  }

  private int audits(UUID subject, String outcome) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE action = 'tracking.shared.read' AND subject_id = ? AND outcome = ?",
        Integer.class,
        subject.toString(),
        outcome);
  }

  private ResultActions grant(UUID seeker, UUID specialist, boolean notes) throws Exception {
    return mvc.perform(
        post("/v1/tracking/shares")
            .with(seeker(seeker))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(
                    Map.of("specialistId", specialist.toString(), "includesNotes", notes))));
  }

  private void push(UUID user, Map<?, ?> entry) throws Exception {
    mvc.perform(
            post("/v1/tracking/sync/push")
                .with(seeker(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "deviceId",
                            "phone-1",
                            "entries",
                            List.of(entry),
                            "deletions",
                            List.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected.length()").value(0));
  }

  private static Map<String, Object> entry(String code, Object value, Instant at) {
    return entry(UUID.randomUUID(), code, value, at);
  }

  private static Map<String, Object> entry(UUID id, String code, Object value, Instant at) {
    return Map.of(
        "clientEntryId",
        id.toString(),
        "element",
        Map.of("code", code, "version", "1"),
        "observedOn",
        YESTERDAY,
        "timeZone",
        "Africa/Douala",
        "value",
        value,
        "recordedAt",
        at.toString());
  }
}
