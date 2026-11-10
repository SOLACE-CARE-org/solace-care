package care.solace.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The Phase 2 gate (implementation-plan.md): no API or event exposes notes. Only the owner's own
 * pull returns them, and only the sync and sharing code reads the note table (threat model TR-4).
 */
class NotesNeverLeaveTest extends TrackingStackTest {

  private static final String NOTE = "only-mine-" + UUID.randomUUID();

  @Test
  void aNoteReachesNoEndpointNoEventAndNoAuditEntryExceptTheOwnersPull() throws Exception {
    UUID seeker = UUID.randomUUID();
    consent(seeker);
    String day = LocalDate.now().minusDays(1).toString();
    mvc.perform(
            post("/v1/tracking/sync/push")
                .with(seeker(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "deviceId", "phone-1",
                            "entries",
                                List.of(
                                    entry("note", NOTE, day),
                                    entry("mood", 2, day),
                                    entry("flow_intensity", "heavy", day)),
                            "deletions", List.of()))))
        .andExpect(status().isOk());

    // The owner's own pull is the one place the note comes back.
    assertThat(body(get("/v1/tracking/sync/pull"), seeker(seeker))).contains(NOTE);

    RequestPostProcessor me = seeker(seeker);
    for (String path :
        List.of(
            "/v1/tracking/cycles",
            "/v1/tracking/modules",
            "/v1/tracking/recommendations",
            "/v1/tracking/profile",
            "/v1/tracking/context-answers",
            "/v1/tracking/shares",
            "/v1/tracking/elements")) {
      assertThat(body(get(path), me)).as(path).doesNotContain(NOTE);
    }
    var specialist = specialist(UUID.randomUUID());
    assertThat(body(get("/v1/tracking/patients/" + seeker + "/entries"), specialist))
        .doesNotContain(NOTE);
    var staff = staff("ops-notes", "N. Ebai", "OPERATIONS", "CLINICAL_REVIEWER");
    assertThat(body(get("/admin/v1/tracking/modules"), staff)).doesNotContain(NOTE);

    // Nothing written for others carries it: outgoing events and the audit log.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class,
                "%" + NOTE + "%"))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE coalesce(details::text, '') LIKE ? OR coalesce(reason, '') LIKE ?",
                Integer.class,
                "%" + NOTE + "%",
                "%" + NOTE + "%"))
        .isZero();
  }

  @Test
  void onlySyncAndSharingReadTheNoteTable() throws IOException {
    Path main = Path.of("src/main/java");
    try (Stream<Path> files = Files.walk(main)) {
      List<String> readers =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> read(p).contains("entry_note"))
              .map(p -> p.getFileName().toString())
              .toList();
      assertThat(readers).containsOnly("Sync.java");
    }
  }

  private static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private String body(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      RequestPostProcessor who)
      throws Exception {
    return mvc.perform(request.with(who))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private static Map<String, Object> entry(String code, Object value, String day) {
    return Map.of(
        "clientEntryId",
        UUID.randomUUID().toString(),
        "element",
        Map.of("code", code, "version", "1"),
        "observedOn",
        day,
        "timeZone",
        "Africa/Douala",
        "value",
        value,
        "recordedAt",
        Instant.now().minusSeconds(60).toString());
  }
}
