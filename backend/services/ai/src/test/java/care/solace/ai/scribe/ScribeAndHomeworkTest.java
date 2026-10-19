package care.solace.ai.scribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.ai.gateway.EmbeddingRoute;
import care.solace.ai.gateway.ModelRoute;
import care.solace.ai.gateway.RouteCatalog;
import care.solace.ai.gateway.SpeechRoute;
import care.solace.ai.safety.SafetyChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The session scribe, notes, homework and the companion (Phase 4; D57) with scripted models: the
 * pipeline drafts from a local transcript and leaves the assessment empty, only the note's
 * specialist reaches it, signing deletes the transcript and the audio, refusing deletes the audio
 * at once, and the companion follows the plan and hands off on a risk signal.
 */
@SpringBootTest(
    properties = {
      "spring.grpc.server.inprocess.name=ai-scribe",
      "spring.grpc.server.port=0",
      "spring.kafka.listener.auto-startup=false",
      "solace.grpc.client-registration=",
      "solace.ai.routes.SESSION_SCRIBE[0]=local",
      "solace.ai.routes.COMPANION[0]=local"
    })
@AutoConfigureMockMvc
@Testcontainers
class ScribeAndHomeworkTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:0.8.0-pg16")
              .asCompatibleSubstituteFor("postgres"));

  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, byte[]> BUCKET = new ConcurrentHashMap<>();
  static final String TRANSCRIPT =
      "Seeker: I slept 6 hours most nights. I still get nervous before team meetings."
          + " Specialist: Let's do one thought record before each team meeting.";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionsEnded sessions;
  @MockitoSpyBean SafetyChecks safety;

  private final RequestPostProcessor superAdmin = staff("sa-1", "S. Admin", "SUPER_ADMIN");
  private final RequestPostProcessor reviewer = staff("cr-1", "Dr. Grace", "CLINICAL_REVIEWER");

  @Test
  void theScribeDraftsALocalNoteThatOnlyItsSpecialistReviewsAndSigns() throws Exception {
    goLive("SESSION_SCRIBE");
    UUID booking = UUID.randomUUID();
    UUID specialist = UUID.randomUUID();
    UUID seeker = UUID.randomUUID();
    String key = "scribe-audio/" + booking + ".ogg";
    BUCKET.put(key, new byte[] {1, 2, 3});

    sessions.record(new SessionsEnded.Ended(booking, specialist, true, key, "en", false), seeker);
    assertThat(sessions.draftNext()).isTrue();
    assertThat(sessions.draftNext()).isFalse();

    var list =
        json(mvc.perform(get("/v1/specialist/notes").with(member(specialist, "SPECIALIST"))));
    assertThat(list).hasSize(1);
    assertThat(list.get(0).path("status").asText()).isEqualTo("DRAFT");
    assertThat(list.get(0).path("preview").asText()).contains("slept 6 hours");
    String id = list.get(0).path("id").asText();
    // Another specialist, or a seeker, never reaches it.
    mvc.perform(get("/v1/specialist/notes/" + id).with(member(UUID.randomUUID(), "SPECIALIST")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v1/specialist/notes/" + id).with(member(seeker, "SEEKER")))
        .andExpect(status().isForbidden());

    var note =
        json(mvc.perform(get("/v1/specialist/notes/" + id).with(member(specialist, "SPECIALIST"))));
    assertThat(note.path("aiDrafted").asBoolean()).isTrue();
    assertThat(note.path("canRedraft").asBoolean()).isTrue();
    assertThat(section(note, "A")).isEmpty();
    assertThat(section(note, "S")).contains("slept 6 hours");
    // Every read of the note is on record.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'note.read' AND target_id = ?",
                Integer.class,
                id))
        .isPositive();
    // At rest the sections are sealed.
    byte[] sealed =
        jdbc.queryForObject("SELECT sealed FROM session_note WHERE id = ?::uuid", byte[].class, id);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("slept");

    // The other format, drafted again from the transcript.
    mvc.perform(
            post("/v1/specialist/notes/" + id + "/redraft")
                .with(member(specialist, "SPECIALIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"DAP\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("DAP"))
        .andExpect(jsonPath("$.sections.length()").value(3));

    // The assessment is the specialist's: signing waits for it.
    mvc.perform(
            post("/v1/specialist/notes/" + id + "/signature")
                .with(member(specialist, "SPECIALIST")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("notes.incomplete"));
    mvc.perform(
            put("/v1/specialist/notes/" + id)
                .with(member(specialist, "SPECIALIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"format\":\"DAP\",\"sections\":[{\"key\":\"D\",\"text\":\"Sleeping more.\"},"
                        + "{\"key\":\"A\",\"text\":\"Engaged and making progress.\"},"
                        + "{\"key\":\"P\",\"text\":\"Thought records before meetings.\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/v1/specialist/notes/" + id + "/signature")
                .with(member(specialist, "SPECIALIST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SIGNED"))
        .andExpect(jsonPath("$.canRedraft").value(false));
    // Signed: the transcript and the session audio are gone.
    assertThat(BUCKET).doesNotContainKey(key);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM note_transcript", Integer.class)).isZero();
    mvc.perform(
            put("/v1/specialist/notes/" + id)
                .with(member(specialist, "SPECIALIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"DAP\",\"sections\":[]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("notes.not-a-draft"));
  }

  @Test
  void audioRefusedDuringTheSessionIsDeletedAtOnce() {
    UUID booking = UUID.randomUUID();
    String key = "scribe-audio/" + booking + ".ogg";
    BUCKET.put(key, new byte[] {1});
    sessions.record(
        new SessionsEnded.Ended(booking, UUID.randomUUID(), false, key, "fr", false),
        UUID.randomUUID());
    assertThat(BUCKET).doesNotContainKey(key);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM session_note WHERE booking_id = ?", Integer.class, booking))
        .isZero();
  }

  @Test
  void homeworkIsSetAfterASessionAndTheCompanionGuidesItAndHandsOffOnARiskSignal()
      throws Exception {
    UUID booking = UUID.randomUUID();
    UUID specialist = UUID.randomUUID();
    UUID seeker = UUID.randomUUID();
    String assign =
        "{\"exercise\":\"THOUGHT_RECORD\",\"frequency\":\"TIMES\",\"times\":2,"
            + "\"note\":\"Try one before each team meeting.\",\"companionGuided\":true}";
    // No session with this seeker yet.
    mvc.perform(
            post("/v1/specialist/bookings/" + booking + "/homework")
                .with(member(specialist, "SPECIALIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(assign))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("homework.no-session"));

    sessions.record(new SessionsEnded.Ended(booking, specialist, false, null, "en", false), seeker);
    var homework =
        json(
            mvc.perform(
                    post("/v1/specialist/bookings/" + booking + "/homework")
                        .with(member(specialist, "SPECIALIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assign))
                .andExpect(status().isCreated()));
    String id = homework.path("id").asText();
    assertThat(homework.path("target").asInt()).isEqualTo(2);

    var mine = json(mvc.perform(get("/v1/me/homework").with(member(seeker, "SEEKER"))));
    assertThat(mine).hasSize(1);
    mvc.perform(
            get("/v1/me/homework/" + id + "/companion").with(member(UUID.randomUUID(), "SEEKER")))
        .andExpect(status().isNotFound());

    mvc.perform(get("/v1/me/homework/" + id + "/companion").with(member(seeker, "SEEKER")))
        .andExpect(jsonPath("$.step").value("SITUATION"))
        .andExpect(
            jsonPath("$.prompt")
                .value("Let's do your first thought record. What happened that upset you today?"));
    answer(id, seeker, "My manager asked for an update and I froze.")
        .andExpect(jsonPath("$.step").value("THOUGHT"))
        .andExpect(
            jsonPath("$.record.SITUATION").value("My manager asked for an update and I froze."));
    answer(id, seeker, "They will think I am not good at my job.");
    answer(id, seeker, "Embarrassed.");
    answer(id, seeker, "I was caught off guard, and it was fine later.")
        .andExpect(jsonPath("$.step").value("DONE"))
        .andExpect(jsonPath("$.done").value(1))
        .andExpect(jsonPath("$.finished").value(false))
        .andExpect(jsonPath("$.prompt").value(org.hamcrest.Matchers.containsString("saved")));
    // The specialist sees the count, never the words.
    var progress =
        json(
            mvc.perform(
                get("/v1/specialist/bookings/" + booking + "/homework")
                    .with(member(specialist, "SPECIALIST"))));
    assertThat(progress.get(0).path("done").asInt()).isEqualTo(1);
    assertThat(progress.toString()).doesNotContain("froze");

    // A risk signal stops the companion and opens urgent help; the answer is not stored.
    doReturn(new SafetyChecks.Decision(UUID.randomUUID(), true))
        .when(safety)
        .check(eq("COMPANION"), any(), eq(seeker), eq("flag-for-test"), any());
    answer(id, seeker, "flag-for-test")
        .andExpect(jsonPath("$.crisis").value(true))
        .andExpect(jsonPath("$.record").isEmpty());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private void goLive(String feature) throws Exception {
    var overview = json(mvc.perform(get("/admin/v1/ai/features").with(superAdmin)));
    String draft = "";
    for (JsonNode f : overview.path("features")) {
      if (f.path("feature").asText().equals(feature)) {
        draft = f.path("candidate").path("id").asText();
      }
    }
    var evaluation =
        json(mvc.perform(post("/admin/v1/ai/versions/" + draft + "/evaluation").with(superAdmin)));
    assertThat(evaluation.path("evaluation").path("passed").asBoolean())
        .as(evaluation.toString())
        .isTrue();
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/sign-off").with(reviewer))
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/ai/versions/" + draft + "/promotion").with(superAdmin))
        .andExpect(status().isOk());
    mvc.perform(
            put("/admin/v1/ai/features/" + feature + "/switch")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Evaluated and signed off\"}"))
        .andExpect(status().isOk());
  }

  private ResultActions answer(String homework, UUID seeker, String text) throws Exception {
    return mvc.perform(
            post("/v1/me/homework/" + homework + "/companion")
                .with(member(seeker, "SEEKER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("answer", text))))
        .andExpect(status().isOk());
  }

  private static String section(JsonNode note, String key) {
    for (JsonNode s : note.path("sections")) {
      if (s.path("key").asText().equals(key)) {
        return s.path("text").asText();
      }
    }
    throw new AssertionError(key);
  }

  private static RequestPostProcessor member(UUID id, String role) {
    return jwt()
        .jwt(
            t ->
                t.issuer("http://localhost:8180/realms/solace-members")
                    .subject("kc-" + id)
                    .claim("preferred_username", id.toString())
                    .claim("realm_access", Map.of("roles", List.of(role))));
  }

  private static RequestPostProcessor staff(String id, String name, String... roles) {
    return jwt()
        .jwt(
            t ->
                t.issuer("http://localhost:8180/realms/solace-staff")
                    .subject(id)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  private static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(
        result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * A local model that keeps to what was said: for a note it puts the transcript under every
   * section asked for; for a reflection it repeats the person's first answer.
   */
  static String scripted(String system, String user) {
    try {
      if (user.contains("Transcript:\n")) {
        String transcript =
            user.substring(user.indexOf("Transcript:\n") + "Transcript:\n".length());
        String sections = "";
        for (String line : user.split("\n")) {
          if (line.startsWith("Sections: ")) {
            sections = line.substring("Sections: ".length());
          }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : sections.split(", ")) {
          out.put(k, k.equals("A") ? "A model must not write this." : transcript);
        }
        return JSON.writeValueAsString(out);
      }
      return "Thank you for writing this down. Noticing the thought is already a step.";
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @TestConfiguration
  static class Fakes {

    @Bean
    @Primary
    RouteCatalog scriptedRoutes() {
      ModelRoute local =
          new ModelRoute() {
            @Override
            public String name() {
              return "local";
            }

            @Override
            public boolean local() {
              return true;
            }

            @Override
            public Reply call(String system, String user) {
              return new Reply(scripted(system, user), 10, 10);
            }
          };
      SpeechRoute speech =
          new SpeechRoute() {
            @Override
            public String name() {
              return "local-speech";
            }

            @Override
            public boolean local() {
              return true;
            }

            @Override
            public String transcribe(byte[] audio, String filename, String language) {
              return TRANSCRIPT;
            }
          };
      return new RouteCatalog(List.of(local), List.of(speech), List.<EmbeddingRoute>of()) {};
    }

    @Bean
    @Primary
    AudioStore memoryAudio() {
      return new AudioStore() {
        @Override
        public Optional<byte[]> read(String key) {
          return Optional.ofNullable(BUCKET.get(key));
        }

        @Override
        public void delete(String key) {
          BUCKET.remove(key);
        }
      };
    }
  }
}
