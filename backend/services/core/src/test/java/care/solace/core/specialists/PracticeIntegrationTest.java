package care.solace.core.specialists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Several professions per specialist and several specialties per profession (D62): the catalogue
 * staff keep, applying with more than one profession, adding to a practice after approval, and
 * requests for specialties the catalogue does not have yet.
 */
class PracticeIntegrationTest extends SpecialistFlowTest {

  private final RequestPostProcessor ops = staff("ops-p1", "Nadine Ebai", "OPERATIONS");
  private final RequestPostProcessor second = staff("ops-p2", "Jean Fotso", "OPERATIONS");
  private final RequestPostProcessor superAdmin = staff("sa-p1", "Super", "SUPER_ADMIN");

  @Test
  void staffKeepSpecialtiesUnderMainOnesAndLinkThemToProfessions() throws Exception {
    catalogue("specialties", "grief-counselling", "Grief counselling", "Deuil", "counselling", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentCode").value("counselling"));
    // One level deep: nothing sits under a specialty that is itself under another.
    catalogue("specialties", "grief-children", "Children", "Enfants", "grief-counselling", null)
        .andExpect(status().isBadRequest());
    catalogue("specialties", "counselling", "Counselling", "Conseil", "psychiatry", null)
        .andExpect(status().isBadRequest());
    // A profession needs the specialties it allows, and only professions take them.
    catalogue(
            "professions",
            "licensed-counsellor",
            "Licensed counsellor",
            "Conseiller agréé",
            null,
            List.of())
        .andExpect(status().isBadRequest());
    catalogue("specialties", "x-y", "X", "Y", null, List.of("counselling"))
        .andExpect(status().isBadRequest());
    catalogue(
            "professions",
            "licensed-counsellor",
            "Licensed counsellor",
            "Conseiller agréé",
            null,
            List.of("counselling", "grief-counselling"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.specialties[1]").value("grief-counselling"));

    mvc.perform(get("/admin/v1/catalogue").with(ops))
        .andExpect(
            jsonPath("$.professions[?(@.code == 'licensed-counsellor')].specialties[*]")
                .value(org.hamcrest.Matchers.hasItems("counselling", "grief-counselling")));
    mvc.perform(get("/v1/catalogue/professions").header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(
            jsonPath(
                    "$[?(@.code == 'licensed-counsellor')].specialties[?(@.code == 'grief-counselling')].label")
                .value("Deuil"))
        .andExpect(
            jsonPath(
                    "$[?(@.code == 'licensed-counsellor')].specialties[?(@.code == 'grief-counselling')].parentCode")
                .value("counselling"));
    // Support may look, not change.
    catalogue("specialties", "z", "Z", "Z", null, null, staff("s", "S", "SUPPORT"))
        .andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'catalogue.professions.save'"
                    + " AND target_id = 'licensed-counsellor'",
                Integer.class))
        .isPositive();
  }

  @Test
  void aSpecialistAppliesWithTwoProfessionsAndIsFoundUnderEach() throws Exception {
    catalogue(
        "specialties",
        "couples-counselling",
        "Couples counselling",
        "Conseil conjugal",
        "counselling",
        null);
    catalogue(
        "professions",
        "licensed-counsellor",
        "Licensed counsellor",
        "Conseiller agréé",
        null,
        List.of("counselling", "couples-counselling"));

    Applicant applicant =
        submittedApplicant(
            "+237695000001",
            "Ewondo Mireille",
            """
            {"professions":[
               {"profession":"clinical-psychologist","licenceNumber":"ONPC-77","licensingBody":"ONPC",
                "specialties":["clinical-psychology"]},
               {"profession":"licensed-counsellor","licenceNumber":"CA-12","licensingBody":"Ordre",
                "specialties":["couples-counselling"]}],
             "yearsInPractice":10,"focusAreas":["relationships"],"bio":"I work with couples."}
            """,
            "clinical-psychologist",
            "licensed-counsellor");
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(applicant.token())))
        .andExpect(jsonPath("$.professions.length()").value(2))
        .andExpect(
            jsonPath("$.documents[?(@.kind == 'LICENCE')].profession")
                .value(
                    org.hamcrest.Matchers.hasItems(
                        "clinical-psychologist", "licensed-counsellor")));

    act(post(app(applicant, "/review")).with(ops)).andExpect(status().isOk());
    item(applicant, ops, "IDENTITY", "{\"verified\":true}");
    act(
        put(app(applicant, "/bio"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"language\":\"fr\",\"text\":\"Je travaille avec les couples.\"}"));
    item(applicant, ops, "PROFILE", "{\"verified\":true}");
    item(applicant, ops, "PAYOUT", "{\"verified\":true}");
    language(applicant, ops, "en", "NATIVE");
    language(applicant, ops, "fr", "FLUENT");
    verifyProfession(applicant, ops, "clinical-psychologist");
    verifySpecialty(applicant, ops, "clinical-psychologist", "clinical-psychology")
        .andExpect(jsonPath("$.practice.verified").value(false))
        .andExpect(jsonPath("$.verifiedItems").value(5));
    verifyProfession(applicant, ops, "licensed-counsellor");
    verifySpecialty(applicant, ops, "licensed-counsellor", "couples-counselling")
        .andExpect(jsonPath("$.practice.verified").value(true))
        .andExpect(jsonPath("$.verifiedItems").value(6));
    // Changing a checked item withdraws a waiting approval (D29).
    act(post(app(applicant, "/approval-proposal")).with(ops)).andExpect(status().isOk());
    act(put(specialist(applicant, "/professions/licensed-counsellor/verification"))
            .with(second)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"verified\":false}"))
        .andExpect(jsonPath("$.approvalProposal").doesNotExist());
    verifyProfession(applicant, ops, "licensed-counsellor");
    act(post(app(applicant, "/approval-proposal")).with(ops)).andExpect(status().isOk());
    act(post(app(applicant, "/approval")).with(second))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.professions[0].status").value("APPROVED"))
        .andExpect(jsonPath("$.professions[1].status").value("APPROVED"))
        .andExpect(jsonPath("$.professions[1].specialties[0].status").value("APPROVED"));
    confirmDraft(applicant);

    // Found under each specialty, and under the main specialty of a specialty under it.
    assertThat(ids(search("language=en&specialty=clinical-psychology"))).contains(applicant.id());
    assertThat(ids(search("language=en&specialty=couples-counselling"))).contains(applicant.id());
    assertThat(ids(search("language=en&specialty=counselling"))).contains(applicant.id());
    assertThat(ids(search("language=en&specialty=psychiatry"))).doesNotContain(applicant.id());
    assertThat(ids(search("language=en&q=couples"))).contains(applicant.id());
    JsonNode card = card(search("language=en&specialty=counselling"), applicant.id());
    assertThat(card.path("professionLabel").asText()).isEqualTo("Clinical psychologist");
    assertThat(card.path("professions").toString())
        .isEqualTo("[\"Clinical psychologist\",\"Licensed counsellor\"]");
    assertThat(card.path("specialties").toString()).contains("Couples counselling");
    mvc.perform(
            get("/v1/catalogue/specialists/" + applicant.id())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(
            jsonPath("$.specialties").value(org.hamcrest.Matchers.hasItem("Conseil conjugal")));
  }

  @Test
  void anApplicationKeepsWithinTheProfessionLimit() throws Exception {
    setting("solace.specialists.max-professions", "1");
    try {
      String token = signedUpSpecialist("+237695000002", "Limit Test");
      mvc.perform(
              put("/v1/me/specialist-application/practice")
                  .headers(bearer(token))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"professions":[
                         {"profession":"clinical-psychologist","licenceNumber":"1","licensingBody":"O",
                          "specialties":["clinical-psychology"]},
                         {"profession":"psychiatrist","licenceNumber":"2","licensingBody":"O",
                          "specialties":["psychiatry"]}],
                       "yearsInPractice":3,"focusAreas":["anxiety"],"bio":"Bio"}
                      """))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("specialist.too-many-professions"));
    } finally {
      setting("solace.specialists.max-professions", "3");
    }
  }

  @Test
  void anApprovedSpecialistAddsAProfessionThatTwoPeopleApprove() throws Exception {
    Applicant specialist = approvedSpecialist("+237695000003", "Atangana Paul", ops);
    confirmDraft(specialist);

    addProfession(specialist, "psychiatrist", "psychiatry")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.professions[1].profession").value("psychiatrist"))
        .andExpect(jsonPath("$.professions[1].status").value("PENDING"));
    addProfession(specialist, "psychiatrist", "psychiatry")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.already-claimed"));
    // Not listed under it while it waits.
    assertThat(ids(search("language=en&specialty=psychiatry"))).doesNotContain(specialist.id());
    mvc.perform(get("/admin/v1/specialist-additions").with(ops))
        .andExpect(
            jsonPath(
                    "$[?(@.specialistId == '%s' && @.kind == 'PROFESSION')].professionLabel"
                        .formatted(specialist.id()))
                .value("Psychiatrist"));

    verifyProfession(specialist, ops, "psychiatrist").andExpect(status().isOk());
    act(post(specialist(specialist, "/professions/psychiatrist/approval")).with(second))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.profession-not-ready"));
    verifySpecialty(specialist, ops, "psychiatrist", "psychiatry")
        .andExpect(jsonPath("$.professions[1].specialties[0].status").value("PENDING"));
    act(post(specialist(specialist, "/professions/psychiatrist/approval")).with(ops))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.same-approver"));
    act(post(specialist(specialist, "/professions/psychiatrist/approval")).with(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.professions[1].status").value("APPROVED"))
        .andExpect(jsonPath("$.professions[1].decision.by.name").value("Jean Fotso"))
        .andExpect(jsonPath("$.professions[1].specialties[0].status").value("APPROVED"));
    assertThat(ids(search("language=en&specialty=psychiatry"))).contains(specialist.id());
    awaitDecisionPush(specialist, 1);

    // A specialty under an approved profession: a note is required, and one reviewer approves it.
    addSpecialty(specialist, "clinical-psychologist", "clinical-psychology", "Again")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.already-claimed"));
    addSpecialty(specialist, "psychiatrist", "counselling", "Trained")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.unknown-choice"));

    // The last approved specialty of an approved profession stays; so does the last profession.
    mvc.perform(
            delete("/v1/me/specialist-professions/psychiatrist/specialties/psychiatry")
                .headers(bearer(specialist.token())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.last-approved"));
    mvc.perform(
            delete("/v1/me/specialist-professions/psychiatrist")
                .headers(bearer(specialist.token())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.professions.length()").value(1));
    assertThat(ids(search("language=en&specialty=psychiatry"))).doesNotContain(specialist.id());
    mvc.perform(
            delete("/v1/me/specialist-professions/clinical-psychologist")
                .headers(bearer(specialist.token())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.last-approved"));

    // A rejected profession can be added again; rejecting tells the specialist why.
    addProfession(specialist, "psychiatrist", "psychiatry").andExpect(status().isOk());
    act(post(specialist(specialist, "/professions/psychiatrist/rejection"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"The licence number does not match the register.\"}"))
        .andExpect(
            jsonPath("$.professions[?(@.profession == 'psychiatrist')].status").value("REJECTED"));
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(specialist.token())))
        .andExpect(
            jsonPath("$.professions[?(@.profession == 'psychiatrist')].reviewNote")
                .value("The licence number does not match the register."))
        .andExpect(
            jsonPath("$.professions[?(@.profession == 'psychiatrist')].specialties[0].status")
                .value("DECLINED"));
    awaitDecisionPush(specialist, 2);
    // Neither the push nor the audit trail names the profession to anyone outside staff.
    assertThat(
            jdbc.queryForList(
                "SELECT text FROM notification WHERE kind = 'DECISION_MADE' AND account_id = ?::uuid",
                String.class,
                specialist.id()))
        .allSatisfy(text -> assertThat(text).doesNotContainIgnoringCase("psychiatr"));
  }

  @Test
  void anAddedSpecialtyNeedsANoteAndOneReviewer() throws Exception {
    catalogue(
        "specialties",
        "trauma-psychology",
        "Trauma",
        "Psychotraumatologie",
        "clinical-psychology",
        null);
    catalogue(
        "professions",
        "clinical-psychologist",
        "Clinical psychologist",
        "Psychologue clinicienne",
        null,
        List.of("clinical-psychology", "trauma-psychology"));
    Applicant specialist = approvedSpecialist("+237695000004", "Bella Ruth", ops);
    confirmDraft(specialist);

    mvc.perform(
            multipart("/v1/me/specialist-professions/clinical-psychologist/specialties")
                .param("specialty", "trauma-psychology")
                .param("note", " ")
                .headers(bearer(specialist.token())))
        .andExpect(status().isBadRequest());
    var request =
        multipart("/v1/me/specialist-professions/clinical-psychologist/specialties")
            .file(new MockMultipartFile("file", "certificate.pdf", "x", PDF))
            .param("specialty", "trauma-psychology")
            .param("note", "Two-year certificate in trauma therapy, 2021.")
            .headers(bearer(specialist.token()));
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.professions[0].specialties[1].status").value("PENDING"));

    JsonNode record = json(act(get("/admin/v1/specialists/" + specialist.id()).with(ops)));
    JsonNode added = record.path("professions").get(0).path("specialties").get(1);
    assertThat(added.path("note").asText())
        .isEqualTo("Two-year certificate in trauma therapy, 2021.");
    String certificate = added.path("certificateId").asText();
    act(get(app(specialist, "/documents/" + certificate)).with(ops)).andExpect(status().isOk());
    mvc.perform(get("/admin/v1/specialist-additions").with(ops))
        .andExpect(
            jsonPath(
                    "$[?(@.specialistId == '%s' && @.kind == 'SPECIALTY')].specialty"
                        .formatted(specialist.id()))
                .value("trauma-psychology"));

    verifySpecialty(specialist, ops, "clinical-psychologist", "trauma-psychology")
        .andExpect(jsonPath("$.professions[0].specialties[1].status").value("APPROVED"));
    assertThat(ids(search("language=en&specialty=trauma-psychology"))).contains(specialist.id());
    // Withdrawn at once; booked sessions are a booking matter and stay.
    mvc.perform(
            delete(
                    "/v1/me/specialist-professions/clinical-psychologist/specialties/trauma-psychology")
                .headers(bearer(specialist.token())))
        .andExpect(status().isOk());
    assertThat(ids(search("language=en&specialty=trauma-psychology")))
        .doesNotContain(specialist.id());
  }

  @Test
  void aRequestedSpecialtyJoinsTheCatalogueForEveryoneWithTheProfession() throws Exception {
    Applicant specialist = approvedSpecialist("+237695000005", "Nkeng Brigitte", ops);
    confirmDraft(specialist);

    requestSpecialty(specialist, "clinical-psychologist", "Perinatal psychology", true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.specialtyRequests[0].status").value("OPEN"))
        .andExpect(jsonPath("$.specialtyRequests[0].proposedName").value("Perinatal psychology"));
    JsonNode open = request(specialist, "Perinatal psychology");
    String id = open.path("id").asText();
    assertThat(open.path("certificateId").asText()).isNotBlank();
    assertThat(open.path("professionLabel").asText()).isEqualTo("Clinical psychologist");

    // Specialists staff may look; changing the catalogue needs F on it.
    mvc.perform(get("/admin/v1/specialty-requests").with(staff("c", "C", "CLINICAL_REVIEWER")))
        .andExpect(status().isOk());
    mvc.perform(get("/admin/v1/specialty-requests").with(staff("r", "R", "CRISIS_RESPONDER")))
        .andExpect(status().isForbidden());
    create(id, "perinatal-psychology", "clinical-psychology", staff("c", "C", "CLINICAL_REVIEWER"))
        .andExpect(status().isForbidden());
    // Under a main specialty only, with a free code.
    create(id, "perinatal-psychology", "perinatal-psychology", ops)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.specialty-conflict"));
    create(id, "psychiatry", "clinical-psychology", ops)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.specialty-conflict"));
    create(id, "perinatal-psychology", "clinical-psychology", ops)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.specialty").value("perinatal-psychology"));
    create(id, "perinatal-psychology-2", "clinical-psychology", ops)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.request-closed"));

    // In the catalogue for every clinical psychologist, and waiting for review for this one.
    mvc.perform(get("/v1/catalogue/professions").header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(
            jsonPath("$[?(@.code == 'clinical-psychologist')].specialties[*].code")
                .value(org.hamcrest.Matchers.hasItem("perinatal-psychology")));
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(specialist.token())))
        .andExpect(
            jsonPath(
                    "$.professions[0].specialties[?(@.specialty == 'perinatal-psychology')].status")
                .value("PENDING"))
        .andExpect(jsonPath("$.specialtyRequests[0].status").value("CREATED"));
    verifySpecialty(specialist, ops, "clinical-psychologist", "perinatal-psychology")
        .andExpect(status().isOk());
    assertThat(ids(search("language=en&specialty=perinatal-psychology"))).contains(specialist.id());
    awaitDecisionPush(specialist, 2);

    // Matching answers with a specialty that exists, linking it to the profession.
    requestSpecialty(specialist, "clinical-psychologist", "Couples work", false);
    String couples = request(specialist, "Couples work").path("id").asText();
    mvc.perform(
            post("/admin/v1/specialty-requests/" + couples + "/match")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"specialty\":\"counselling\",\"reason\":\"Same as Counselling.\"}"))
        .andExpect(jsonPath("$.status").value("MATCHED"));
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(specialist.token())))
        .andExpect(
            jsonPath("$.professions[0].specialties[?(@.specialty == 'counselling')].status")
                .value("PENDING"));

    // Declining tells the specialist why.
    requestSpecialty(specialist, "clinical-psychologist", "Astrology", false);
    String astrology = request(specialist, "Astrology").path("id").asText();
    mvc.perform(
            post("/admin/v1/specialty-requests/" + astrology + "/decline")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Not a clinical specialty.\"}"))
        .andExpect(jsonPath("$.status").value("DECLINED"));
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(specialist.token())))
        .andExpect(
            jsonPath("$.specialtyRequests[0].reviewNote").value("Not a clinical specialty."));

    // At most the setting's number open at once; an open one can be withdrawn.
    setting("solace.specialists.max-open-specialty-requests", "1");
    try {
      requestSpecialty(specialist, "clinical-psychologist", "One", false)
          .andExpect(status().isOk());
      requestSpecialty(specialist, "clinical-psychologist", "Two", false)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("specialist.too-many-requests"));
      String one = request(specialist, "One").path("id").asText();
      mvc.perform(delete("/v1/me/specialty-requests/" + one).headers(bearer(specialist.token())))
          .andExpect(jsonPath("$.specialtyRequests[0].status").value("WITHDRAWN"));
    } finally {
      setting("solace.specialists.max-open-specialty-requests", "3");
    }

    List<Map<String, Object>> audit =
        jdbc.queryForList(
            "SELECT action, actor_id FROM audit_log WHERE action LIKE 'specialty-requests.%'"
                + " AND actor_id = 'ops-p1'");
    assertThat(audit)
        .extracting(row -> row.get("action"))
        .contains(
            "specialty-requests.create", "specialty-requests.match", "specialty-requests.decline");
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private ResultActions catalogue(
      String kind, String code, String en, String fr, String parent, List<String> specialties)
      throws Exception {
    return catalogue(kind, code, en, fr, parent, specialties, ops);
  }

  private ResultActions catalogue(
      String kind,
      String code,
      String en,
      String fr,
      String parent,
      List<String> specialties,
      RequestPostProcessor who)
      throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("labelEn", en);
    body.put("labelFr", fr);
    body.put("enabled", true);
    body.put("reason", "D62 test");
    if (parent != null) {
      body.put("parentCode", parent);
    }
    if (specialties != null) {
      body.put("specialties", specialties);
    }
    return mvc.perform(
        put("/admin/v1/catalogue/" + kind + "/" + code)
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(body)));
  }

  private void setting(String key, String value) throws Exception {
    mvc.perform(
            put("/admin/v1/settings/" + key)
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("value", value, "reason", "D62 test"))))
        .andExpect(status().isOk());
  }

  private String signedUpSpecialist(String phone, String name) throws Exception {
    mvc.perform(
            post("/v1/specialist-signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "fullName",
                            name,
                            "phone",
                            phone,
                            "email",
                            phone.substring(1) + "@example.cm",
                            "termsVersion",
                            "v1",
                            "codeOfConductVersion",
                            "v1",
                            "appLanguage",
                            "en"))))
        .andExpect(status().isAccepted());
    return signIn(phone);
  }

  private ResultActions addProfession(Applicant a, String profession, String specialty)
      throws Exception {
    return mvc.perform(
        multipart("/v1/me/specialist-professions")
            .file(new MockMultipartFile("file", "licence.pdf", "x", PDF))
            .param("profession", profession)
            .param("licenceNumber", "LIC-" + profession)
            .param("licensingBody", "Ordre")
            .param("specialties", specialty)
            .headers(bearer(a.token())));
  }

  private ResultActions addSpecialty(Applicant a, String profession, String specialty, String note)
      throws Exception {
    return mvc.perform(
        multipart("/v1/me/specialist-professions/" + profession + "/specialties")
            .param("specialty", specialty)
            .param("note", note)
            .headers(bearer(a.token())));
  }

  private ResultActions requestSpecialty(
      Applicant a, String profession, String name, boolean certificate) throws Exception {
    var request = multipart("/v1/me/specialty-requests");
    if (certificate) {
      request.file(new MockMultipartFile("file", "certificate.png", "x", PNG));
    }
    return mvc.perform(
        request
            .param("profession", profession)
            .param("name", name)
            .param("note", "Trained in it for two years.")
            .headers(bearer(a.token())));
  }

  private ResultActions create(String id, String code, String parent, RequestPostProcessor who)
      throws Exception {
    return mvc.perform(
        post("/admin/v1/specialty-requests/" + id + "/creation")
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(
                    Map.of(
                        "code", code,
                        "labelEn", "Perinatal psychology",
                        "labelFr", "Psychologie périnatale",
                        "parentCode", parent,
                        "reason", "Asked for by a specialist."))));
  }

  /** The open request with this name from this specialist. */
  private JsonNode request(Applicant a, String name) throws Exception {
    for (JsonNode r : json(mvc.perform(get("/admin/v1/specialty-requests").with(ops)))) {
      if (r.path("specialistId").asText().equals(a.id())
          && r.path("proposedName").asText().equals(name)) {
        return r;
      }
    }
    throw new AssertionError("no open request " + name);
  }

  private void awaitDecisionPush(Applicant a, int count) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM notification WHERE kind = 'DECISION_MADE'"
                                + " AND account_id = ?::uuid",
                            Integer.class,
                            a.id()))
                    .isGreaterThanOrEqualTo(count));
  }

  private void confirmDraft(Applicant a) throws Exception {
    mvc.perform(
        post("/v1/me/specialist-application/bio-confirmation")
            .headers(bearer(a.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private JsonNode search(String query) throws Exception {
    return json(mvc.perform(get("/v1/catalogue/specialists?" + query + "&limit=50")));
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.path("items").forEach(item -> ids.add(item.path("id").asText()));
    return ids;
  }

  private static JsonNode card(JsonNode page, String id) {
    for (JsonNode item : page.path("items")) {
      if (item.path("id").asText().equals(id)) {
        return item;
      }
    }
    throw new AssertionError("no card for " + id);
  }
}
