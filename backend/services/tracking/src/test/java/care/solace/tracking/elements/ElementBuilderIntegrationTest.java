package care.solace.tracking.elements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Admin-defined elements end to end (Tracking Service Spec, Elements; D61): drafted from a
 * registered view, reviewed by someone else, published into the library with no migration, bound by
 * a module, and versioned.
 */
class ElementBuilderIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-1", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-1", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  private static String body(String code, String values, String frValues) {
    return """
        {"code":"%s","kind":"DAY_STATE","valueType":"ENUM","constraints":{"values":%s},
         "inputView":"choice_chips","viewProps":{},"icon":"brain",
         "labels":{"en":{"name":"Headache","values":{"none":"None","some":"Some","bad":"Bad","worst":"Worst"}},
                   "fr":{"name":"Mal de tête","values":%s}}}
        """
        .formatted(code, values, frValues);
  }

  private static final String FR_VALUES =
      "{\"none\":\"Aucun\",\"some\":\"Un peu\",\"bad\":\"Fort\",\"worst\":\"Très fort\"}";

  @Test
  void theRegistryListsTheViewsTheAppShips() throws Exception {
    mvc.perform(get("/admin/v1/tracking/input-views").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$[1].key").value("duration_stepper"))
        .andExpect(jsonPath("$[1].props.start.type").value("integer"));
    // The stepper's start is a view prop now, not app code.
    mvc.perform(get("/v1/tracking/elements").with(seeker(UUID.randomUUID())))
        .andExpect(jsonPath("$[?(@.code == 'sleep_minutes')].viewProps.start").value(420));
  }

  @Test
  void anElementIsDraftedReviewedBySomeoneElsePublishedAndBound() throws Exception {
    String code = "headache_" + UUID.randomUUID().toString().substring(0, 6);
    String values = "[\"none\",\"some\",\"bad\",\"worst\"]";
    // French value labels missing: saved, but not ready.
    var draft =
        json(
            send(post("/admin/v1/tracking/element-versions"), ops, body(code, values, "{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.signals[0]").value("daily_value"))
                .andExpect(jsonPath("$.minAppVersion").value("1.0.0"))
                .andExpect(jsonPath("$.problems[0]").value("values")));
    String id = draft.path("id").asText();
    String path = "/admin/v1/tracking/element-versions/" + id;
    mvc.perform(post(path + "/submission").with(ops)).andExpect(status().isConflict());
    send(put(path), ops, body(code, values, FR_VALUES))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.problems.length()").value(0));
    mvc.perform(post(path + "/submission").with(ops))
        .andExpect(jsonPath("$.status").value("IN_REVIEW"));

    // Not in the library until published: no phone sees it and no module can bind it.
    mvc.perform(get("/v1/tracking/elements").with(seeker(UUID.randomUUID())))
        .andExpect(jsonPath("$[?(@.code == '%s')]".formatted(code)).isEmpty());
    String module = module();
    bind(module, code, "1").andExpect(status().isBadRequest());

    // Only a Clinical reviewer approves.
    send(post(path + "/review"), ops, "{\"approve\":true}").andExpect(status().isForbidden());
    send(post(path + "/review"), reviewer, "{\"approve\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvedBy").value("Dr. Fomba Grace"))
        .andExpect(jsonPath("$.reviews[0].decision").value("APPROVED"));
    mvc.perform(post(path + "/publication").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    // In the library at once, with no migration and no release.
    mvc.perform(get("/v1/tracking/elements").with(seeker(UUID.randomUUID())))
        .andExpect(
            jsonPath("$[?(@.code == '%s')].labels.fr.values.worst".formatted(code))
                .value("Très fort"));
    mvc.perform(get("/admin/v1/tracking/elements").with(ops))
        .andExpect(jsonPath("$[?(@.code == '%s')].version".formatted(code)).value("1"));
    bind(module, code, "1").andExpect(status().isOk());
    // A published version is frozen, and cannot retire while a module binds it.
    send(put(path), ops, body(code, values, FR_VALUES)).andExpect(status().isConflict());
    mvc.perform(post(path + "/retirement").with(ops)).andExpect(status().isConflict());
    mvc.perform(get(path).with(ops)).andExpect(jsonPath("$.boundBy").value(1));

    // A new version: adding a value is minor, removing one is major. One open version a code.
    String more = "[\"none\",\"some\",\"bad\",\"worst\",\"extra\"]";
    String moreFr =
        "{\"none\":\"Aucun\",\"some\":\"Un peu\",\"bad\":\"Fort\",\"worst\":\"Très fort\",\"extra\":\"x\"}";
    String next =
        json(send(post("/admin/v1/tracking/element-versions"), ops, body(code, more, moreFr))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("1.1")))
            .path("id")
            .asText();
    send(post("/admin/v1/tracking/element-versions"), ops, body(code, more, moreFr))
        .andExpect(status().isConflict());
    send(
            put("/admin/v1/tracking/element-versions/" + next),
            ops,
            body(code, "[\"none\",\"some\",\"bad\"]", FR_VALUES))
        .andExpect(jsonPath("$.version").value("2"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM element WHERE code = ? AND status = 'PUBLISHED'",
                Integer.class,
                code))
        .isEqualTo(1);
  }

  @Test
  void whoDraftedAnElementCannotApproveIt() throws Exception {
    var both = staff("dual-1", "A. Nkem", "OPERATIONS", "CLINICAL_REVIEWER");
    String code = "bloating_" + UUID.randomUUID().toString().substring(0, 6);
    String id =
        json(send(
                    post("/admin/v1/tracking/element-versions"),
                    both,
                    body(code, "[\"none\",\"some\",\"bad\",\"worst\"]", FR_VALUES))
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    String path = "/admin/v1/tracking/element-versions/" + id;
    mvc.perform(post(path + "/submission").with(both)).andExpect(status().isOk());
    mvc.perform(get(path).with(both)).andExpect(jsonPath("$.canApprove").value(false));
    send(post(path + "/review"), both, "{\"approve\":true}").andExpect(status().isConflict());
    // Returned with comments, it is a draft again.
    send(post(path + "/review"), reviewer, "{\"approve\":false,\"comments\":\"Name it plainly\"}")
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.reviews[0].comments").value("Name it plainly"));
    mvc.perform(post(path + "/publication").with(both)).andExpect(status().isConflict());
  }

  @Test
  void aViewThatDoesNotTakeTheValueTypeIsAProblemAndBadShapesAreRefused() throws Exception {
    String code = "energy_" + UUID.randomUUID().toString().substring(0, 6);
    send(
            post("/admin/v1/tracking/element-versions"),
            ops,
            """
            {"code":"%s","kind":"DAY_STATE","valueType":"BOOL","constraints":{},
             "inputView":"duration_stepper","icon":"sun",
             "labels":{"en":{"name":"Energy"},"fr":{"name":"Énergie"}}}
            """
                .formatted(code))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.problems[0]").value("inputView"))
        .andExpect(jsonPath("$.minAppVersion").value("1.0.0"));
    send(
            post("/admin/v1/tracking/element-versions"),
            ops,
            """
            {"code":"%s_b","kind":"DAY_STATE","valueType":"BOOL","constraints":{},
             "inputView":"toggle","icon":"sun","labels":{"en":{"name":"Energy"}}}
            """
                .formatted(code))
        .andExpect(status().isBadRequest());
    // Content editors look but do not draft.
    send(
            post("/admin/v1/tracking/element-versions"),
            staff("ce-1", "B. Tabi", "CONTENT_EDITOR"),
            body(code + "_c", "[\"none\",\"some\"]", FR_VALUES))
        .andExpect(status().isForbidden());
  }

  private ResultActions send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      RequestPostProcessor who,
      String body)
      throws Exception {
    return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private String module() throws Exception {
    String code = "mod_" + UUID.randomUUID().toString().substring(0, 6);
    return json(send(
                post("/admin/v1/tracking/modules"),
                ops,
                "{\"code\":\"" + code + "\",\"accentColour\":\"track-pink\"}")
            .andExpect(status().isCreated()))
        .path("id")
        .asText();
  }

  private ResultActions bind(String versionId, String code, String version) throws Exception {
    return send(
        put("/admin/v1/tracking/versions/" + versionId + "/elements"),
        ops,
        "[{\"code\":\"" + code + "\",\"version\":\"" + version + "\",\"required\":true}]");
  }
}
