package care.solace.tracking.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The module builder and the pattern check end to end (Tracking Service Spec; D51): draft, review
 * by another person, publish, enrol, log, a rule fires, guards, dismissal and withdrawal.
 */
class ModuleBuilderIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-1", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-1", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  private static final String LONG_CYCLES =
      "[{\"signal\":\"menstrual_cycle.length_days\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gt\",\"threshold\":35,\"count\":3}]";

  @Test
  void aModuleIsDraftedReviewedBySomeoneElseAndPublished() throws Exception {
    String code = "cycle_" + UUID.randomUUID().toString().substring(0, 6);
    var version = publishedCycleModule(code);
    assertThat(version.path("status").asText()).isEqualTo("PUBLISHED");

    mvc.perform(get("/v1/tracking/modules").with(seeker(UUID.randomUUID())))
        .andExpect(jsonPath("$[?(@.code == '%s')].name.fr".formatted(code)).value("SOPK et cycle"))
        .andExpect(jsonPath("$[?(@.code == '%s')].elements.length()".formatted(code)).value(3));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE '%tracking.module.published%'",
                Integer.class))
        .isPositive();
    // A published version is frozen.
    mvc.perform(
            put("/admin/v1/tracking/versions/" + version.path("id").asText() + "/elements")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"code\":\"mood\",\"version\":\"1\",\"required\":true}]"))
        .andExpect(status().isConflict());
  }

  @Test
  void thePresentationAndGroupsMustFitTheBoundElements() throws Exception {
    String id = draft("pres_" + UUID.randomUUID().toString().substring(0, 6), ops);
    // A trend on an element that is not bound is refused.
    presentation(id, "{\"trends\":[\"daily_value:mood\"]}").andExpect(status().isBadRequest());
    presentation(
            id,
            "{\"episode\":{\"model\":\"menstrual_cycle\","
                + "\"dayLabel\":{\"en\":\"cycle day {{n}}\",\"fr\":\"jour {{n}} du cycle\"}},"
                + "\"trends\":[\"menstrual_cycle.length_days\"],"
                + "\"quickLog\":\"flow_intensity\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.presentation.episode.model").value("menstrual_cycle"))
        .andExpect(jsonPath("$.problems.length()").value(0));
    // Saving the details leaves the presentation as it is.
    mvc.perform(get("/admin/v1/tracking/versions/" + id).with(ops))
        .andExpect(jsonPath("$.presentation.quickLog").value("flow_intensity"));
    // A group whose members are apart is refused.
    String label = "\"labels\":{\"en\":\"Period\",\"fr\":\"Règles\"}";
    mvc.perform(
            put("/admin/v1/tracking/versions/" + id + "/elements")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"code\":\"flow_intensity\",\"version\":\"1\",\"required\":true,"
                        + "\"group\":{\"code\":\"period\","
                        + label
                        + "}},{\"code\":\"note\",\"version\":\"1\",\"required\":false},"
                        + "{\"code\":\"period_start\",\"version\":\"1\",\"required\":true,"
                        + "\"group\":{\"code\":\"period\","
                        + label
                        + "}}]"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/admin/v1/tracking/versions/" + id + "/elements")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"code\":\"flow_intensity\",\"version\":\"1\",\"required\":true,"
                        + "\"group\":{\"code\":\"period\","
                        + label
                        + "}},{\"code\":\"period_start\",\"version\":\"1\",\"required\":true,"
                        + "\"group\":{\"code\":\"period\","
                        + label
                        + "}},{\"code\":\"note\",\"version\":\"1\",\"required\":false}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.elements[1].group.labels.fr").value("Règles"))
        .andExpect(jsonPath("$.problems.length()").value(0));
    // Unbinding the quick-log element leaves the presentation pointing nowhere: not ready.
    mvc.perform(
            put("/admin/v1/tracking/versions/" + id + "/elements")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"code\":\"period_start\",\"version\":\"1\",\"required\":true}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.problems[0]").value("presentation"));
  }

  private ResultActions presentation(String id, String body) throws Exception {
    return mvc.perform(
        put("/admin/v1/tracking/versions/" + id + "/presentation")
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void theDrafterCannotApproveAndOnlyClinicalReviewersCan() throws Exception {
    var both = staff("both-1", "Two Hats", "OPERATIONS", "CLINICAL_REVIEWER");
    String id = draft("same_" + UUID.randomUUID().toString().substring(0, 6), both);
    mvc.perform(post("/admin/v1/tracking/versions/" + id + "/submission").with(both))
        .andExpect(status().isOk());
    review(id, true, both)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("tracking.same-person"));
    review(id, true, ops).andExpect(status().isForbidden());
    // Returned with comments, it goes back to draft.
    review(id, false, reviewer).andExpect(jsonPath("$.status").value("DRAFT"));
    mvc.perform(get("/admin/v1/tracking/versions/" + id).with(staff("ce", "C", "CONTENT_EDITOR")))
        .andExpect(jsonPath("$.reviews[0].decision").value("RETURNED"));
    mvc.perform(
            post("/admin/v1/tracking/modules")
                .with(staff("ce", "C", "CONTENT_EDITOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"x\",\"accentColour\":\"brand\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void diagnosticWordingIsRefusedAndTestRunsUseMadeUpHistories() throws Exception {
    String id = draft("words_" + UUID.randomUUID().toString().substring(0, 6), ops);
    rule(id, "pcos.long_cycles", LONG_CYCLES, "You may have PCOS.", "Vous avez peut-être un SOPK.")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("tracking.blocked-wording"));
    rule(
            id,
            "pcos.long_cycles",
            LONG_CYCLES,
            "Your last {n} cycles were longer than {threshold} days.",
            "Vos {n} derniers cycles ont duré plus de {threshold} jours.")
        .andExpect(status().isCreated());
    var results =
        json(mvc.perform(post("/admin/v1/tracking/versions/" + id + "/test-run").with(ops)));
    List<String> fires = new ArrayList<>();
    for (JsonNode r : results) {
      if (r.path("fires").asBoolean()) {
        fires.add(r.path("history").asText());
      }
    }
    // No "unless" on this rule, so the history on hormonal treatment fires too.
    assertThat(fires).containsExactlyInAnyOrder("long_cycles", "long_cycles_hormonal_treatment");
  }

  @Test
  void aRuleFiresOnceThenItsGuardsHoldAndAnEditWithdrawsIt() throws Exception {
    String code = "cyc_" + UUID.randomUUID().toString().substring(0, 6);
    publishedCycleModule(code);
    UUID user = UUID.randomUUID();
    consent(user);
    mvc.perform(
            post("/v1/tracking/modules/" + code + "/enrolment")
                .with(seeker(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNoContent());

    // Four period starts 40 days apart: three complete long cycles.
    LocalDate start = LocalDate.now().minusDays(130);
    List<Map<String, Object>> entries = new ArrayList<>();
    List<String> clientIds = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String day = start.plusDays(40L * i).toString();
      String id = UUID.randomUUID().toString();
      clientIds.add(id);
      entries.add(entry(id, "period_start", day, day));
    }
    push(user, entries).andExpect(status().isOk());
    var recs =
        json(
            mvc.perform(
                get("/v1/tracking/recommendations")
                    .with(seeker(user))
                    .header("Accept-Language", "fr")));
    assertThat(recs).hasSize(1);
    assertThat(recs.get(0).path("outcome").asText()).isEqualTo("INVITE");
    assertThat(recs.get(0).path("message").asText())
        .isEqualTo("Vos 3 derniers cycles ont duré plus de 35 jours.");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE '%tracking.recommendation.created%'",
                Integer.class))
        .isPositive();

    // Logging again: the open recommendation blocks a second one.
    push(
        user,
        List.of(
            entry(
                UUID.randomUUID().toString(),
                "flow_intensity",
                LocalDate.now().minusDays(1).toString(),
                "light")));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rule_evaluation WHERE user_id = ? AND result IN ('BLOCKED_OPEN', 'BLOCKED_COOLDOWN')",
                Integer.class,
                user))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation WHERE user_id = ?", Integer.class, user))
        .isEqualTo(1);

    // Deleting a period start breaks the pattern: the unseen recommendation is withdrawn quietly.
    String recId = recs.get(0).path("id").asText();
    push(user, List.of(), List.of(Map.of("clientEntryId", clientIds.get(2))));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM recommendation WHERE id = ?::uuid", String.class, recId))
        .isEqualTo("SUPERSEDED");
    mvc.perform(get("/v1/tracking/recommendations").with(seeker(user)))
        .andExpect(jsonPath("$.length()").value(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rule_evaluation WHERE recommendation_id = ?::uuid AND result = 'WITHDRAWN'",
                Integer.class,
                recId))
        .isEqualTo(1);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private JsonNode publishedCycleModule(String code) throws Exception {
    String id = draft(code, ops);
    rule(
        id,
        "pcos.long_cycles",
        LONG_CYCLES,
        "Your last {n} cycles were longer than {threshold} days.",
        "Vos {n} derniers cycles ont duré plus de {threshold} jours.");
    mvc.perform(post("/admin/v1/tracking/versions/" + id + "/submission").with(ops))
        .andExpect(status().isOk());
    review(id, true, reviewer).andExpect(status().isOk());
    return json(
        mvc.perform(post("/admin/v1/tracking/versions/" + id + "/publication").with(ops))
            .andExpect(status().isOk()));
  }

  /** A complete draft: details in EN and FR, an image, three elements. Returns the version id. */
  private String draft(String code, RequestPostProcessor who) throws Exception {
    String id =
        json(mvc.perform(
                    post("/admin/v1/tracking/modules")
                        .with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"accentColour\":\"track-pink\"}"))
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    String image =
        json(mvc.perform(
                    multipart("/admin/v1/tracking/images")
                        .file(
                            new MockMultipartFile(
                                "file", "card.jpg", "image/jpeg", jpeg(1200, 800)))
                        .with(who))
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    // The wrong shape is refused.
    mvc.perform(
            multipart("/admin/v1/tracking/images")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", jpeg(1200, 1200)))
                .with(who))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("tracking.image-size"));
    mvc.perform(
            put("/admin/v1/tracking/versions/" + id + "/details")
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.ofEntries(
                            Map.entry("nameEn", "PCOS & cycle"),
                            Map.entry("nameFr", "SOPK et cycle"),
                            Map.entry("descriptionEn", "Periods, flow, skin and hair"),
                            Map.entry("descriptionFr", "Règles, flux, peau et pilosité"),
                            Map.entry("imageId", image),
                            Map.entry("imageAltEn", "A calendar"),
                            Map.entry("imageAltFr", "Un calendrier"),
                            Map.entry("audienceEn", "For anyone who has periods"),
                            Map.entry("audienceFr", "Pour toute personne qui a des règles")))))
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/tracking/versions/" + id + "/submission").with(who))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("tracking.not-ready"));
    mvc.perform(
            put("/admin/v1/tracking/versions/" + id + "/elements")
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"code\":\"flow_intensity\",\"version\":\"1\",\"required\":true},"
                        + "{\"code\":\"period_start\",\"version\":\"1\",\"required\":true},"
                        + "{\"code\":\"note\",\"version\":\"1\",\"required\":false}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.problems.length()").value(0));
    return id;
  }

  private ResultActions rule(String versionId, String code, String conditions, String en, String fr)
      throws Exception {
    return mvc.perform(
        post("/admin/v1/tracking/versions/" + versionId + "/rules")
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"code\":\""
                    + code
                    + "\",\"conditions\":"
                    + conditions
                    + ",\"outcome\":\"INVITE\","
                    + "\"specialtyCodes\":[\"gynaecology\"],\"messageEn\":\""
                    + en
                    + "\",\"messageFr\":\""
                    + fr
                    + "\",\"cooldownDays\":30}"));
  }

  private ResultActions review(String id, boolean approve, RequestPostProcessor who)
      throws Exception {
    return mvc.perform(
        post("/admin/v1/tracking/versions/" + id + "/review")
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"approve\":" + approve + ",\"comments\":\"Checked thresholds\"}"));
  }

  private ResultActions push(UUID user, List<Map<String, Object>> entries) throws Exception {
    return push(user, entries, List.of());
  }

  private ResultActions push(
      UUID user, List<Map<String, Object>> entries, List<Map<String, String>> deletions)
      throws Exception {
    return mvc.perform(
        post("/v1/tracking/sync/push")
            .with(seeker(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(
                    Map.of("deviceId", "p", "entries", entries, "deletions", deletions))));
  }

  private static Map<String, Object> entry(String id, String code, String day, Object value) {
    return Map.of(
        "clientEntryId",
        id,
        "element",
        Map.of("code", code, "version", "1"),
        "observedOn",
        day,
        "timeZone",
        "Africa/Douala",
        "value",
        value,
        "recordedAt",
        Instant.now().toString());
  }

  private static byte[] jpeg(int w, int h) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
    return out.toByteArray();
  }

  @SuppressWarnings("unused")
  private ResultActions deleteEntry(UUID user, String id) throws Exception {
    return mvc.perform(delete("/v1/tracking/observations/" + id).with(seeker(user)));
  }
}
