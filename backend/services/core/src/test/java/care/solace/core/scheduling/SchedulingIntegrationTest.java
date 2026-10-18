package care.solace.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * Slots, holds and time off (epic 4) for a specialist made through the real app and portal flows:
 * weekly hours Monday 09:00 to 12:00 in Douala, 50-minute audio sessions, 10-minute breaks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulingIntegrationTest extends SpecialistFlowTest {

  private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");

  private Applicant specialist;
  private LocalDate monday;

  @BeforeAll
  void specialist() throws Exception {
    specialist =
        approvedSpecialist("+237696000001", "Slot Keeper", staff("ops-s", "R", "OPERATIONS"));
    mvc.perform(
        post("/v1/me/specialist-application/bio-confirmation")
            .headers(bearer(specialist.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    monday = LocalDate.now(DOUALA).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
  }

  @Test
  void slotsComeFromWeeklyHoursAndAHoldTakesOne() throws Exception {
    JsonNode slots = slots();
    assertThat(slots.path("timeZone").asText()).isEqualTo("Africa/Douala");
    assertThat(slots.path("durationMinutes").asInt()).isEqualTo(50);
    assertThat(slots.path("slots")).hasSize(3); // 09:00, 10:00, 11:00 with 10-minute breaks
    String ten = at(10);
    assertThat(available(slots, ten)).isTrue();

    JsonNode hold =
        hold(ten)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .transform(this::read);
    assertThat(hold.path("end").asText())
        .isEqualTo(monday.atTime(10, 50).atZone(DOUALA).toInstant().toString());
    assertThat(available(slots(), ten)).isFalse();
    // Someone else cannot hold it meanwhile.
    hold(ten).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("slot.taken"));

    mvc.perform(delete("/v1/slot-holds/" + hold.path("holdId").asText()))
        .andExpect(status().isNoContent());
    assertThat(available(slots(), ten)).isTrue();
    // A start that is not offered cannot be held.
    hold(monday.atTime(10, 15).atZone(DOUALA).toInstant().toString())
        .andExpect(status().isConflict());
  }

  @Test
  void timeOffBlocksStartsAndNothingMayOverlap() throws Exception {
    String start = at(9);
    String end = monday.atTime(10, 0).atZone(DOUALA).toInstant().toString();
    JsonNode off =
        read(
            mvc.perform(
                    post("/v1/me/time-off")
                        .headers(bearer(specialist.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"start\":\"%s\",\"end\":\"%s\",\"reason\":\"PERSONAL\"}"
                                .formatted(start, end)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(available(slots(), start)).isFalse();
    assertThat(available(slots(), at(10))).isTrue();

    // Overlapping time off is refused.
    mvc.perform(
            post("/v1/me/time-off")
                .headers(bearer(specialist.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(at(9), at(11))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("scheduling.time-off-conflict"));
    // So is a booking, by the database itself.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO calendar_entry (id, specialist_id, during, kind, booking_id)"
                        + " VALUES (?, ?::uuid, tstzrange(?, ?), 'BOOKING', ?)",
                    UUID.randomUUID(),
                    specialist.id(),
                    Timestamp.from(monday.atTime(9, 30).atZone(DOUALA).toInstant()),
                    Timestamp.from(monday.atTime(10, 30).atZone(DOUALA).toInstant()),
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);

    // The calendar shows the day's open starts and the time off.
    mvc.perform(
            get("/v1/me/calendar")
                .headers(bearer(specialist.token()))
                .param("from", monday.toString())
                .param("days", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].openStarts.length()").value(2))
        .andExpect(jsonPath("$.days[0].timeOff[0].reason").value("PERSONAL"));

    mvc.perform(
            delete("/v1/me/time-off/" + off.path("id").asText())
                .headers(bearer(specialist.token())))
        .andExpect(status().isNoContent());
    assertThat(available(slots(), start)).isTrue();
  }

  @Test
  void onlyListedSpecialistsAndOfferedFormatsHaveSlots() throws Exception {
    mvc.perform(
            get("/v1/catalogue/specialists/" + specialist.id() + "/slots")
                .param("modality", "VIDEO"))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/v1/catalogue/specialists/" + UUID.randomUUID() + "/slots")
                .param("modality", "AUDIO"))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/v1/catalogue/specialists/" + specialist.id() + "/slots")
                .param("modality", "PHONE"))
        .andExpect(status().isBadRequest());
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private String at(int hour) {
    return monday.atTime(hour, 0).atZone(DOUALA).toInstant().toString();
  }

  private JsonNode slots() throws Exception {
    return read(
        mvc.perform(
                get("/v1/catalogue/specialists/" + specialist.id() + "/slots")
                    .param("modality", "AUDIO")
                    .param("from", monday.toString())
                    .param("days", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private org.springframework.test.web.servlet.ResultActions hold(String start) throws Exception {
    return mvc.perform(
        post("/v1/slot-holds")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"specialistId\":\"%s\",\"start\":\"%s\",\"modality\":\"AUDIO\"}"
                    .formatted(specialist.id(), start)));
  }

  private static boolean available(JsonNode slots, String start) {
    for (JsonNode slot : slots.path("slots")) {
      if (java.time.Instant.parse(slot.path("start").asText())
          .equals(java.time.Instant.parse(start))) {
        return slot.path("available").asBoolean();
      }
    }
    throw new AssertionError("no slot at " + start);
  }

  private JsonNode read(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
