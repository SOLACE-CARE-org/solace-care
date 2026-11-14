package care.solace.tracking.episodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Admin-defined episode models end to end (Tracking Service Spec, Episode models; admin screen 33;
 * D61): drafted from published elements, tested on the synthetic histories, reviewed by someone
 * else, published with no migration or restart, rebuilt over what people logged before, and
 * versioned.
 */
class EpisodeModelBuilderIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-ep", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-ep", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  private static final String METRICS =
      """
      [{"code":"length_days","kind":"LENGTH_DAYS","labels":{"en":"Attack length","fr":"Durée de la crise"}},
       {"code":"strong_days","kind":"DAYS_WITH_VALUE","value":"strong",
        "labels":{"en":"Strong days","fr":"Jours forts"}}]
      """;

  private static String model(String code, String start, String pain, String metrics) {
    return """
        {"code":"%s","startElement":"%s","activityElement":"%s","activeValues":["mild","strong"],
         "gapDays":2,"span":"UNTIL_END","metrics":%s,
         "labels":{"en":"Migraine attack","fr":"Crise de migraine"}}
        """
        .formatted(code, start, pain, metrics);
  }

  @Test
  void aModelIsDraftedTestedReviewedPublishedAndRebuiltOverWhatWasLogged() throws Exception {
    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String start = publishedElement("mig_start_" + sfx, "EVENT", "DATE", "{}", "event_marker");
    String pain =
        publishedElement(
            "mig_pain_" + sfx,
            "DAY_STATE",
            "ENUM",
            "{\"values\":[\"none\",\"mild\",\"strong\"]}",
            "choice_chips");
    String code = "migraine_" + sfx;

    // Someone logs two attacks before the model exists.
    UUID user = UUID.randomUUID();
    consent(user);
    LocalDate first = LocalDate.now().minusDays(20);
    List<Map<String, Object>> entries = new ArrayList<>();
    for (LocalDate s : List.of(first, first.plusDays(10))) {
      entries.add(entry(start, s.toString(), s.toString()));
      entries.add(entry(pain, s.toString(), "strong"));
      entries.add(entry(pain, s.plusDays(1).toString(), "strong"));
      entries.add(entry(pain, s.plusDays(2).toString(), "mild"));
      entries.add(entry(pain, s.plusDays(3).toString(), "none"));
    }
    push(user, entries).andExpect(status().isOk());
    mvc.perform(get("/v1/tracking/episodes/" + code).with(seeker(user)))
        .andExpect(status().isNotFound());

    // The wrong start element and a value the activity element lacks: saved, but not ready.
    var draft =
        json(
            send(
                    post("/admin/v1/tracking/episode-model-versions"),
                    ops,
                    model(code, pain, pain, METRICS.replace("\"strong\",", "\"worst\",")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.signals[0]").value(code + ".length_days")));
    assertThat(texts(draft.path("problems"))).containsExactlyInAnyOrder("start", "metrics");
    String path = "/admin/v1/tracking/episode-model-versions/" + draft.path("id").asText();
    mvc.perform(post(path + "/submission").with(ops)).andExpect(status().isConflict());
    send(put(path), ops, model(code, start, pain, METRICS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.problems.length()").value(0));

    // A made-up history with three attacks; the test run finds them and stores nothing.
    send(
            post("/admin/v1/tracking/synthetic-histories"),
            reviewer,
            """
            {"code":"attacks_%s","labels":{"en":"Three attacks","fr":"Trois crises"},
             "recipe":{"parts":[{"pattern":"episodes","start":"%s","activity":"%s",
                                 "lengths":[10,10,10],"currentDay":2,
                                 "active":[{"value":"strong","days":3}],
                                 "after":{"value":"none","days":2}}]},
             "context":{}}
            """
                .formatted(sfx, start, pain))
        .andExpect(status().isCreated());
    JsonNode attacks = null;
    for (JsonNode r : json(mvc.perform(post(path + "/test-run").with(ops)))) {
      if (r.path("history").asText().equals("attacks_" + sfx)) {
        attacks = r;
      }
    }
    assertThat(attacks).isNotNull();
    assertThat(attacks.path("episodes")).hasSize(4);
    assertThat(attacks.path("episodes").get(0).path("complete").asBoolean()).isTrue();
    assertThat(attacks.path("episodes").get(0).path("metrics").path("strong_days").asInt())
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM episode WHERE model_code = ?", Integer.class, code))
        .isZero();

    // Only a Clinical reviewer who did not draft it approves.
    mvc.perform(post(path + "/submission").with(ops))
        .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    send(post(path + "/review"), ops, "{\"approve\":true}").andExpect(status().isForbidden());
    send(post(path + "/review"), reviewer, "{\"approve\":true}")
        .andExpect(jsonPath("$.approvedBy").value("Dr. Fomba Grace"))
        .andExpect(jsonPath("$.reviews[0].decision").value("APPROVED"));
    mvc.perform(post(path + "/publication").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    // In use at once: the attacks logged before are found, with no new push and no restart.
    var summary =
        json(
            mvc.perform(get("/v1/tracking/episodes/" + code).with(seeker(user)))
                .andExpect(status().isOk()));
    assertThat(summary.path("episodes")).hasSize(2);
    assertThat(summary.path("episodes").get(0).path("complete").asBoolean()).isTrue();
    assertThat(summary.path("episodes").get(0).path("metrics").path("strong_days").asInt())
        .isEqualTo(2);

    // A module that shows its day cannot let it retire; a new version cannot drop a metric.
    String module = moduleUsing(code, start, pain);
    mvc.perform(post(path + "/retirement").with(ops)).andExpect(status().isConflict());
    mvc.perform(get(path).with(ops)).andExpect(jsonPath("$.usedBy").value(1));
    send(put(path), ops, model(code, start, pain, METRICS)).andExpect(status().isConflict());
    String one =
        "[{\"code\":\"length_days\",\"kind\":\"LENGTH_DAYS\",\"labels\":{\"en\":\"Attack length\",\"fr\":\"Durée de la crise\"}}]";
    var next =
        json(
            send(
                    post("/admin/v1/tracking/episode-model-versions"),
                    ops,
                    model(code, start, pain, one))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2)));
    assertThat(texts(next.path("problems"))).containsExactly("metrics");
    send(post("/admin/v1/tracking/episode-model-versions"), ops, model(code, start, pain, one))
        .andExpect(status().isConflict());
    assertThat(module).isNotBlank();
  }

  @Test
  void whoDraftedAModelCannotApproveItAndContentEditorsOnlyLook() throws Exception {
    var both = staff("dual-ep", "A. Nkem", "OPERATIONS", "CLINICAL_REVIEWER");
    String code = "flare_" + UUID.randomUUID().toString().substring(0, 6);
    String body =
        """
        {"code":"%s","startElement":"period_start","activeValues":[],"gapDays":10,
         "span":"UNTIL_NEXT_START",
         "metrics":[{"code":"length_days","kind":"LENGTH_DAYS","labels":{"en":"Length","fr":"Durée"}}],
         "labels":{"en":"Flare","fr":"Poussée"}}
        """
            .formatted(code);
    send(
            post("/admin/v1/tracking/episode-model-versions"),
            staff("ce-ep", "B. Tabi", "CONTENT_EDITOR"),
            body)
        .andExpect(status().isForbidden());
    // Active values without an activity element is a shape the form never sends.
    send(
            post("/admin/v1/tracking/episode-model-versions"),
            both,
            body.replace("\"activeValues\":[]", "\"activeValues\":[\"light\"]"))
        .andExpect(status().isBadRequest());
    String id =
        json(send(post("/admin/v1/tracking/episode-model-versions"), both, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems.length()").value(0)))
            .path("id")
            .asText();
    String path = "/admin/v1/tracking/episode-model-versions/" + id;
    mvc.perform(post(path + "/submission").with(both)).andExpect(status().isOk());
    mvc.perform(get(path).with(both)).andExpect(jsonPath("$.canApprove").value(false));
    send(post(path + "/review"), both, "{\"approve\":true}").andExpect(status().isConflict());
    send(
            post(path + "/review"),
            reviewer,
            "{\"approve\":false,\"comments\":\"Say what a flare is\"}")
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.reviews[0].comments").value("Say what a flare is"));
    mvc.perform(post(path + "/publication").with(both)).andExpect(status().isConflict());
    mvc.perform(
            get("/admin/v1/tracking/episode-model-versions")
                .with(staff("ce-ep", "B. Tabi", "CONTENT_EDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.code == 'menstrual_cycle')].status").value("PUBLISHED"));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private String publishedElement(
      String code, String kind, String type, String constraints, String view) throws Exception {
    String values =
        type.equals("ENUM")
            ? ",\"values\":{\"none\":\"None\",\"mild\":\"Mild\",\"strong\":\"Strong\"}"
            : "";
    String frValues =
        type.equals("ENUM")
            ? ",\"values\":{\"none\":\"Aucune\",\"mild\":\"Légère\",\"strong\":\"Forte\"}"
            : "";
    String id =
        json(send(
                    post("/admin/v1/tracking/element-versions"),
                    ops,
                    """
                    {"code":"%s","kind":"%s","valueType":"%s","constraints":%s,"inputView":"%s",
                     "viewProps":{},"icon":"brain",
                     "labels":{"en":{"name":"Migraine"%s},"fr":{"name":"Migraine"%s}}}
                    """
                        .formatted(code, kind, type, constraints, view, values, frValues))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems.length()").value(0)))
            .path("id")
            .asText();
    String path = "/admin/v1/tracking/element-versions/" + id;
    mvc.perform(post(path + "/submission").with(ops)).andExpect(status().isOk());
    send(post(path + "/review"), reviewer, "{\"approve\":true}").andExpect(status().isOk());
    mvc.perform(post(path + "/publication").with(ops)).andExpect(status().isOk());
    return code;
  }

  /** A module draft that binds the model's elements and shows its current day. */
  private String moduleUsing(String model, String start, String pain) throws Exception {
    String id =
        json(send(
                    post("/admin/v1/tracking/modules"),
                    ops,
                    "{\"code\":\"mod_" + model + "\",\"accentColour\":\"track-pink\"}")
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    send(
            put("/admin/v1/tracking/versions/" + id + "/elements"),
            ops,
            "[{\"code\":\""
                + start
                + "\",\"version\":\"1\",\"required\":true},"
                + "{\"code\":\""
                + pain
                + "\",\"version\":\"1\",\"required\":true}]")
        .andExpect(status().isOk());
    send(
            put("/admin/v1/tracking/versions/" + id + "/presentation"),
            ops,
            "{\"episode\":{\"model\":\""
                + model
                + "\","
                + "\"dayLabel\":{\"en\":\"attack day {{n}}\",\"fr\":\"jour {{n}} de la crise\"}}}")
        .andExpect(status().isOk());
    return id;
  }

  private ResultActions push(UUID user, List<Map<String, Object>> entries) throws Exception {
    return send(
        post("/v1/tracking/sync/push"),
        seeker(user),
        JSON.writeValueAsString(
            Map.of("deviceId", "p", "entries", entries, "deletions", List.of())));
  }

  private ResultActions send(
      MockHttpServletRequestBuilder request, RequestPostProcessor who, String body)
      throws Exception {
    return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static Map<String, Object> entry(String code, String day, Object value) {
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
        Instant.now().toString());
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }
}
