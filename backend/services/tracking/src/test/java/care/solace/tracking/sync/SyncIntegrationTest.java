package care.solace.tracking.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Sync push and pull (Tracking Service Spec; D49). */
class SyncIntegrationTest extends TrackingStackTest {

  private static final String TODAY = LocalDate.now().minusDays(1).toString();

  @Test
  void theLibraryHasTheNineLaunchElementsInBothLanguages() throws Exception {
    // Other tests in this context publish elements of their own, so the launch nine are checked by
    // code rather than by count.
    mvc.perform(get("/v1/tracking/elements").with(seeker(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].code")
                .value(
                    hasItems(
                        "mood",
                        "sleep_minutes",
                        "note",
                        "flow_intensity",
                        "period_start",
                        "period_end",
                        "acne_severity",
                        "excess_hair",
                        "stress_level")))
        .andExpect(jsonPath("$[?(@.code == 'mood')].labels.fr.values.1").value("Très mal"))
        .andExpect(jsonPath("$[?(@.code == 'note')].signals[0]").doesNotExist());
  }

  @Test
  void loggingNeedsConsentAndASeeker() throws Exception {
    UUID user = UUID.randomUUID();
    push(user, entry(UUID.randomUUID(), "mood", TODAY, 3, Instant.now()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("tracking.consent-needed"));
    mvc.perform(
            post("/v1/tracking/sync/push")
                .with(specialist(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(List.of(), List.of())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("tracking.seekers-only"));
  }

  @Test
  void aReplayChangesNothingAndANewerValueSupersedesTheDay() throws Exception {
    UUID user = UUID.randomUUID();
    consent(user);
    UUID first = UUID.randomUUID();
    Instant at = Instant.now().minusSeconds(600);
    var pushed = json(push(user, entry(first, "mood", TODAY, 2, at)).andExpect(status().isOk()));
    String id = pushed.path("accepted").get(0).path("observationId").asText();
    // Same client id again: the same observation, nothing new.
    var replay = json(push(user, entry(first, "mood", TODAY, 5, at)));
    assertThat(replay.path("accepted").get(0).path("observationId").asText()).isEqualTo(id);

    push(user, entry(UUID.randomUUID(), "mood", TODAY, 4, Instant.now()));
    var pulled = json(mvc.perform(get("/v1/tracking/sync/pull").with(seeker(user))));
    assertThat(pulled.path("observations")).hasSize(1);
    assertThat(pulled.path("observations").get(0).path("value").asInt()).isEqualTo(4);
    assertThat(pulled.path("deleted").get(0).asText()).isEqualTo(id);

    // An older entry from an offline phone does not win.
    push(user, entry(UUID.randomUUID(), "mood", TODAY, 1, at.minusSeconds(60)));
    var after = json(mvc.perform(get("/v1/tracking/sync/pull").with(seeker(user))));
    assertThat(after.path("observations").get(0).path("value").asInt()).isEqualTo(4);

    // Pull from the cursor returns only what changed since.
    long cursor = after.path("cursor").asLong();
    mvc.perform(
            get("/v1/tracking/sync/pull").param("since", String.valueOf(cursor)).with(seeker(user)))
        .andExpect(jsonPath("$.observations.length()").value(0))
        .andExpect(jsonPath("$.deleted.length()").value(0));
  }

  @Test
  void valuesAreCheckedAndNotesAreDeletedForReal() throws Exception {
    UUID user = UUID.randomUUID();
    consent(user);
    var result =
        json(
            push(
                user,
                entry(UUID.randomUUID(), "mood", TODAY, 9, Instant.now()),
                entry(UUID.randomUUID(), "flow_intensity", TODAY, "torrential", Instant.now()),
                entry(UUID.randomUUID(), "sleep_minutes", TODAY, 400, Instant.now()),
                entry(
                    UUID.randomUUID(),
                    "stress_level",
                    LocalDate.now().plusDays(3).toString(),
                    "high",
                    Instant.now())));
    assertThat(result.path("rejected").findValuesAsText("code"))
        .containsExactly(
            "tracking.invalid-value",
            "tracking.invalid-value",
            "tracking.invalid-value",
            "tracking.future-day");

    UUID noteEntry = UUID.randomUUID();
    var note =
        json(
            push(
                user,
                entry(noteEntry, "note", TODAY, "Couldn't switch off after work.", Instant.now())));
    String noteId = note.path("accepted").get(0).path("observationId").asText();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_note", Integer.class)).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT value_text FROM observation WHERE id = ?::uuid", String.class, noteId))
        .isNull();
    mvc.perform(get("/v1/tracking/sync/pull").with(seeker(user)))
        .andExpect(jsonPath("$.observations[0].value").value("Couldn't switch off after work."));

    mvc.perform(delete("/v1/tracking/observations/" + noteId).with(seeker(user)))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM entry_note WHERE observation_id = ?::uuid",
                Integer.class,
                noteId))
        .isZero();
    mvc.perform(delete("/v1/tracking/observations/" + noteId).with(seeker(user)))
        .andExpect(status().isNotFound());
    // Someone else's entry is not found either.
    mvc.perform(delete("/v1/tracking/observations/" + noteId).with(seeker(UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  @Test
  void eventsCanBeLoggedManyTimesADay() throws Exception {
    UUID user = UUID.randomUUID();
    consent(user);
    push(
        user,
        entry(UUID.randomUUID(), "period_start", TODAY, TODAY, Instant.now()),
        entry(UUID.randomUUID(), "period_start", TODAY, TODAY, Instant.now()));
    mvc.perform(get("/v1/tracking/sync/pull").with(seeker(user)))
        .andExpect(jsonPath("$.observations.length()").value(2));
  }

  private ResultActions push(UUID user, Map<?, ?>... entries) throws Exception {
    return mvc.perform(
        post("/v1/tracking/sync/push")
            .with(seeker(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(List.of(entries), List.of())));
  }

  private static String body(List<?> entries, List<?> deletions) throws Exception {
    return JSON.writeValueAsString(
        Map.of("deviceId", "phone-1", "entries", entries, "deletions", deletions));
  }

  private static Map<String, Object> entry(
      UUID id, String code, String day, Object value, Instant at) {
    return Map.of(
        "clientEntryId",
        id.toString(),
        "element",
        Map.of("code", code, "version", "1"),
        "observedOn",
        day,
        "timeZone",
        "Africa/Douala",
        "value",
        value,
        "recordedAt",
        at.toString());
  }
}
