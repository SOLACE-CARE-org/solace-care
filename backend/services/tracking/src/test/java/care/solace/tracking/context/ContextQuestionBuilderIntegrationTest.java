package care.solace.tracking.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Admin-defined context questions end to end (Tracking Service Spec, Context questions; admin
 * screen 34; D61): drafted, reviewed by someone else, published with no migration or restart,
 * answered on the phone, asked by a module, and versioned without breaking stored answers.
 */
class ContextQuestionBuilderIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-cq", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-cq", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  private static final String PILL =
      "{\"code\":\"pill\",\"labels\":{\"en\":\"Pill\",\"fr\":\"Pilule\"}}";
  private static final String COIL =
      "{\"code\":\"coil\",\"labels\":{\"en\":\"Coil\",\"fr\":\"Stérilet\"}}";
  private static final String NONE =
      "{\"code\":\"none\",\"labels\":{\"en\":\"None\",\"fr\":\"Aucune\"}}";

  private static String question(String code, String type, String fr, String... options) {
    return """
        {"code":"%s","type":"%s","scope":"ENROLMENT",
         "question":{"en":"Which contraception do you use?","fr":"%s"},"options":[%s]}
        """
        .formatted(code, type, fr, String.join(",", options));
  }

  @Test
  void aQuestionIsDraftedReviewedPublishedAnsweredAskedAndVersioned() throws Exception {
    String code = "contraception_" + UUID.randomUUID().toString().substring(0, 6);
    String base = "/admin/v1/tracking/context-question-versions";

    // No French and one option: saved, but not ready.
    var draft =
        json(
            send(post(base), ops, question(code, "ONE_CHOICE", " ", PILL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1)));
    assertThat(texts(draft.path("problems"))).containsExactly("question", "options");
    String path = base + "/" + draft.path("id").asText();
    mvc.perform(post(path + "/submission").with(ops)).andExpect(status().isConflict());
    send(
            put(path),
            ops,
            question(code, "ONE_CHOICE", "Quelle contraception utilisez-vous ?", PILL, COIL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.problems.length()").value(0))
        .andExpect(jsonPath("$.question.fr").value("Quelle contraception utilisez-vous ?"));

    // Only a Clinical reviewer who did not draft it approves.
    mvc.perform(post(path + "/submission").with(ops))
        .andExpect(jsonPath("$.status").value("IN_REVIEW"));
    send(post(path + "/review"), ops, "{\"approve\":true}").andExpect(status().isForbidden());
    send(post(path + "/review"), reviewer, "{\"approve\":true}")
        .andExpect(jsonPath("$.approvedBy").value("Dr. Fomba Grace"));
    mvc.perform(post(path + "/publication").with(ops))
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    // On the phone at once, and answerable, with no migration and no restart.
    UUID user = UUID.randomUUID();
    consent(user);
    var listed =
        json(mvc.perform(get("/v1/tracking/context-questions").with(seeker(user))))
            .findValues("code");
    assertThat(listed.stream().map(JsonNode::asText)).contains(code);
    send(put("/v1/tracking/context-answers/" + code), seeker(user), "{\"value\":\"coil\"}")
        .andExpect(status().isNoContent());
    send(put("/v1/tracking/context-answers/" + code), seeker(user), "{\"value\":\"patch\"}")
        .andExpect(status().isBadRequest());

    // A module that asks it on enrolment keeps it from retiring.
    String module =
        json(send(
                    post("/admin/v1/tracking/modules"),
                    ops,
                    "{\"code\":\"mod_" + code + "\",\"accentColour\":\"track-pink\"}")
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    send(put("/admin/v1/tracking/versions/" + module + "/questions"), ops, "[\"" + code + "\"]")
        .andExpect(status().isOk());
    mvc.perform(get(path).with(ops)).andExpect(jsonPath("$.usedBy").value(1));
    mvc.perform(post(path + "/retirement").with(ops)).andExpect(status().isConflict());

    // A new version may add an option but not drop one or change the type; one open at a time.
    var next =
        json(
            send(
                    post(base),
                    ops,
                    question(
                        code, "MANY_CHOICES", "Quelle contraception utilisez-vous ?", PILL, NONE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2)));
    assertThat(texts(next.path("problems"))).containsExactly("type", "options");
    send(post(base), ops, question(code, "ONE_CHOICE", "Quelle ?", PILL, COIL))
        .andExpect(status().isConflict());
    send(
            put(base + "/" + next.path("id").asText()),
            ops,
            question(code, "ONE_CHOICE", "Quelle contraception utilisez-vous ?", PILL, COIL, NONE))
        .andExpect(jsonPath("$.problems.length()").value(0));

    // The stored answer is untouched.
    mvc.perform(get("/v1/tracking/context-answers").with(seeker(user)))
        .andExpect(jsonPath("$." + code).value("coil"));
  }

  @Test
  void theLaunchQuestionsAreInUseAndOnlyLookingIsOpenToContentEditors() throws Exception {
    var editor = staff("ce-cq", "B. Tabi", "CONTENT_EDITOR");
    var all =
        json(
            mvc.perform(get("/admin/v1/tracking/context-question-versions").with(editor))
                .andExpect(status().isOk()));
    String launch = null;
    for (JsonNode v : all) {
      if (v.path("code").asText().equals("trying_to_conceive")
          && v.path("status").asText().equals("PUBLISHED")) {
        launch = v.path("id").asText();
        assertThat(v.path("question").path("en").asText()).isEqualTo("Are you trying to conceive?");
      }
    }
    assertThat(launch).isNotNull();
    String path = "/admin/v1/tracking/context-question-versions/" + launch;
    // The PCOS module asks it and its rules read it, so it stays.
    assertThat(json(mvc.perform(get(path).with(ops))).path("usedBy").asInt()).isPositive();
    mvc.perform(post(path + "/retirement").with(ops)).andExpect(status().isConflict());
    send(
            post("/admin/v1/tracking/context-question-versions"),
            editor,
            question("flare_q", "YES_NO", "Avez-vous une poussée ?"))
        .andExpect(status().isForbidden());
  }

  private ResultActions send(
      MockHttpServletRequestBuilder request, RequestPostProcessor who, String body)
      throws Exception {
    return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }
}
