package care.solace.core.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Writing, translating, checking and publishing content, and what the apps read (D44). */
class ContentIntegrationTest extends SpecialistFlowTest {

  @Autowired LegalTexts legal;

  private final RequestPostProcessor editor = staff("ce-1", "M. Atangana", "CONTENT_EDITOR");
  private final RequestPostProcessor reviewer =
      staff("cr-1", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  @Test
  void nothingPublishesUntilBothLanguagesAreDone() throws Exception {
    String id =
        json(create("ARTICLE", "first-session", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.clinical").value("NOT_NEEDED")))
            .path("id")
            .asText();
    create("ARTICLE", "first-session", null).andExpect(status().isConflict());
    // Support can look, not write.
    mvc.perform(
            put("/admin/v1/content/" + id)
                .with(staff("sup", "S", "SUPPORT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("A", "B", "", "")))
        .andExpect(status().isForbidden());
    save(id, draft("What happens in a first session", "You talk.", "", ""))
        .andExpect(jsonPath("$.enDone").value(false));
    done(id, "fr", true)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("content.language-empty"));
    done(id, "en", true).andExpect(jsonPath("$.enDone").value(true));
    publish(id).andExpect(status().isConflict());

    save(
            id,
            draft(
                "What happens in a first session",
                "You talk.",
                "Ce qui se passe lors d'une première séance",
                "Vous parlez."))
        .andExpect(jsonPath("$.enDone").value(true));
    done(id, "fr", true).andExpect(jsonPath("$.canPublish").value(true));
    publish(id).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

    mvc.perform(get("/v1/content/first-session").header("Accept-Language", "fr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Ce qui se passe lors d'une première séance"))
        .andExpect(jsonPath("$.version").value("v1"));
    // An edit waits for the next publication; the apps keep reading v1.
    save(
            id,
            draft(
                "What happens in a first session",
                "You talk, and listen.",
                "Ce qui se passe lors d'une première séance",
                "Vous parlez."))
        .andExpect(jsonPath("$.status").value("EDITED"))
        .andExpect(jsonPath("$.enDone").value(false))
        .andExpect(jsonPath("$.frDone").value(true));
    mvc.perform(get("/v1/content/first-session")).andExpect(jsonPath("$.body").value("You talk."));
  }

  @Test
  void aCrisisResourceNeedsAClinicalCheckBySomeoneElse() throws Exception {
    mvc.perform(get("/v1/content").param("type", "CRISIS_RESOURCE").param("country", "CM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].numbers[1].number").value("117"));
    String id =
        json(mvc.perform(get("/admin/v1/content").with(editor).param("type", "CRISIS_RESOURCE")))
            .get(0)
            .path("id")
            .asText();
    var current = json(mvc.perform(get("/admin/v1/content/" + id).with(editor))).path("draft");
    save(
            id,
            JSON.writeValueAsString(
                Map.of(
                    "titleEn", current.path("titleEn").asText(),
                    "bodyEn", current.path("bodyEn").asText() + " ",
                    "titleFr", current.path("titleFr").asText(),
                    "bodyFr", current.path("bodyFr").asText(),
                    "numbers",
                        List.of(
                            Map.of("service", "ambulance", "number", "119"),
                            Map.of("service", "police", "number", "117"),
                            Map.of("service", "fire", "number", "118"),
                            Map.of("service", "helpline", "number", "1510")))))
        .andExpect(jsonPath("$.clinical").value("NEEDED"))
        .andExpect(jsonPath("$.enDone").value(false));
    done(id, "en", true);
    done(id, "fr", true).andExpect(jsonPath("$.canPublish").value(false));
    mvc.perform(post("/admin/v1/content/" + id + "/clinical-check").with(editor))
        .andExpect(jsonPath("$.clinical").value("REQUESTED"));
    // A content editor cannot approve; a clinical reviewer who did not edit can.
    mvc.perform(post("/admin/v1/content/" + id + "/clinical-approval").with(editor))
        .andExpect(status().isForbidden());
    mvc.perform(post("/admin/v1/content/" + id + "/clinical-approval").with(reviewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clinical").value("APPROVED"))
        .andExpect(jsonPath("$.canPublish").value(true));
    publish(id).andExpect(jsonPath("$.version").value(2));
    mvc.perform(get("/v1/content/emergency-numbers-cm"))
        .andExpect(jsonPath("$.numbers.length()").value(4));
  }

  @Test
  void aPublishedLegalTextBecomesTheVersionPeopleAccept() throws Exception {
    String id = "";
    assertThat(legal.currentVersion("code-of-conduct")).isEmpty();
    for (var row :
        json(mvc.perform(get("/admin/v1/content").with(editor).param("type", "LEGAL")))) {
      if (row.path("slug").asText().equals("code-of-conduct")) {
        id = row.path("id").asText();
      }
    }
    save(id, draft("Code of conduct", "Be kind.", "Code de conduite", "Soyez bienveillant."));
    done(id, "en", true);
    done(id, "fr", true);
    publish(id).andExpect(jsonPath("$.version").value(1));
    // v1, the version specialists already accept, so nobody is asked again.
    assertThat(legal.currentVersion("code-of-conduct")).contains("v1");
    mvc.perform(
            post("/v1/me/legal-acceptance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accepted\":[{\"slug\":\"terms\",\"version\":\"v1\"}]}"))
        .andExpect(status().isUnauthorized());
  }

  private ResultActions create(String type, String slug, String country) throws Exception {
    var body = new java.util.HashMap<String, String>(Map.of("type", type, "slug", slug));
    if (country != null) {
      body.put("country", country);
    }
    return mvc.perform(
        post("/admin/v1/content")
            .with(editor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private static String draft(String titleEn, String bodyEn, String titleFr, String bodyFr)
      throws Exception {
    return JSON.writeValueAsString(
        Map.of("titleEn", titleEn, "bodyEn", bodyEn, "titleFr", titleFr, "bodyFr", bodyFr));
  }

  private ResultActions save(String id, String body) throws Exception {
    return mvc.perform(
        put("/admin/v1/content/" + id)
            .with(editor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions done(String id, String language, boolean done) throws Exception {
    return mvc.perform(
        put("/admin/v1/content/" + id + "/translation-status")
            .with(editor)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"language\":\"" + language + "\",\"done\":" + done + "}"));
  }

  private ResultActions publish(String id) throws Exception {
    return mvc.perform(
        post("/admin/v1/content/" + id + "/publication")
            .with(editor)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Ready\"}"));
  }
}
