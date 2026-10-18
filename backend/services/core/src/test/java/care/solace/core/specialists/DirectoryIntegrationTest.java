package care.solace.core.specialists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Find a specialist and the public profile (epic 3, D31): open to guests, session language first,
 * listed only once approved with a confirmed bilingual bio.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectoryIntegrationTest extends SpecialistFlowTest {

  private Applicant clarisse;
  private Applicant notInFrench;

  @BeforeAll
  void specialists() throws Exception {
    var ops = staff("ops-dir", "Directory Reviewer", "OPERATIONS");
    clarisse = approvedSpecialist("+237697000001", "Ngo Ebode Clarisse", ops);

    // Approved, but their French did not reach a bookable level in the interview.
    notInFrench = fullyVerified("+237697000002", "Tanyi Emmanuel", ops);
    language(notInFrench, ops, "fr", "BELOW_PROFESSIONAL");
    act(post(app(notInFrench, "/approval-proposal")).with(ops));
    act(post(app(notInFrench, "/approval")).with(staff("second-dir", "Second", "OPERATIONS")));
    confirmDraft(notInFrench);
  }

  @Test
  void aSolaceDraftedBioKeepsTheProfileUnlistedUntilTheSpecialistConfirmsIt() throws Exception {
    Applicant fresh =
        approvedSpecialist("+237697000003", "Fouda Aicha", staff("ops-d2", "R", "OPERATIONS"));
    assertThat(ids(search("language=en"))).doesNotContain(fresh.id());
    mvc.perform(get("/v1/catalogue/specialists/" + fresh.id())).andExpect(status().isNotFound());

    mvc.perform(get("/v1/me/specialist-application").headers(bearer(fresh.token())))
        .andExpect(jsonPath("$.draftedBio.language").value("fr"))
        .andExpect(jsonPath("$.draftedBio.confirmed").value(false));
    mvc.perform(
            post("/v1/me/specialist-application/bio-confirmation")
                .headers(bearer(fresh.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"J'accompagne les adultes, à mon rythme et au vôtre.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftedBio.confirmed").value(true))
        .andExpect(
            jsonPath("$.draftedBio.text")
                .value("J'accompagne les adultes, à mon rythme et au vôtre."));

    assertThat(ids(search("language=en"))).contains(fresh.id());
  }

  @Test
  void theSessionLanguageComesFirst() throws Exception {
    confirmDraft(clarisse);
    assertThat(ids(search("language=en"))).contains(clarisse.id(), notInFrench.id());
    // Verified below Professional in French: not bookable in French.
    assertThat(ids(search("language=fr"))).contains(clarisse.id()).doesNotContain(notInFrench.id());

    JsonNode card = card(search("language=en"), clarisse.id());
    assertThat(card.path("professionLabel").asText()).isEqualTo("Clinical psychologist");
    assertThat(card.path("languages").get(0).path("code").asText()).isEqualTo("en");
    assertThat(card.path("languages").get(0).path("level").asText()).isEqualTo("NATIVE");
    assertThat(card.path("offering").path("price").asLong()).isEqualTo(15000);
    assertThat(card.path("focusAreas").get(0).asText()).isEqualTo("Anxiety");
    assertThat(card.has("nextAvailable")).isTrue();

    mvc.perform(get("/v1/catalogue/specialists").param("language", "xx"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/catalogue/specialists")).andExpect(status().isBadRequest());
  }

  @Test
  void theSeekersWordsFindFocusAreasInEitherLanguageWithOrWithoutAccents() throws Exception {
    confirmDraft(clarisse);
    assertThat(ids(search("language=en&q=anxiete"))).contains(clarisse.id());
    assertThat(ids(search("language=en&q=Anxiété"))).contains(clarisse.id());
    assertThat(ids(search("language=en&q=anx"))).contains(clarisse.id());
    assertThat(ids(search("language=en&q=psychologist"))).contains(clarisse.id());
    assertThat(ids(search("language=en&q=gynaecology"))).doesNotContain(clarisse.id());
    assertThat(ids(search("language=en&q=%21%21%21"))).isEmpty();
  }

  @Test
  void filtersNarrowTheList() throws Exception {
    confirmDraft(clarisse);
    // Audio only, 15 000 FCFA, clinical psychology.
    assertThat(ids(search("language=en&modality=AUDIO"))).contains(clarisse.id());
    assertThat(ids(search("language=en&modality=VIDEO"))).doesNotContain(clarisse.id());
    assertThat(ids(search("language=en&maxPrice=10000"))).doesNotContain(clarisse.id());
    assertThat(ids(search("language=en&specialty=clinical-psychology"))).contains(clarisse.id());
    assertThat(ids(search("language=en&specialty=psychiatry"))).doesNotContain(clarisse.id());
    mvc.perform(get("/v1/catalogue/specialties").header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].label").value("Psychologie clinique"));
  }

  @Test
  void theProfileIsInTheCallersLanguage() throws Exception {
    confirmDraft(clarisse);
    mvc.perform(
            get("/v1/catalogue/specialists/" + clarisse.id())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("Ngo Ebode Clarisse"))
        .andExpect(jsonPath("$.professionLabel").value("Psychologue clinicienne"))
        .andExpect(jsonPath("$.specialty").value("clinical-psychology"))
        .andExpect(jsonPath("$.bio").value("Bio en français."))
        .andExpect(jsonPath("$.focusAreas[0]").value("Anxiété"))
        .andExpect(jsonPath("$.offerings[0].modality").value("AUDIO"))
        .andExpect(jsonPath("$.languages.length()").value(2));
    mvc.perform(
            get("/v1/catalogue/specialists/" + clarisse.id())
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(jsonPath("$.bio").value("I help adults with anxiety."));
    mvc.perform(get("/v1/catalogue/specialists/not-a-uuid")).andExpect(status().isBadRequest());
  }

  // --- Helpers ---------------------------------------------------------------------------------

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
