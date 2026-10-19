package care.solace.core.safetyplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The seeker's safety plan (D57): theirs alone, sealed at rest. */
class SafetyPlanIntegrationTest extends SpecialistFlowTest {

  private static final String PLAN =
      """
      {"warningSigns":"Not sleeping, skipping meals","calming":"A walk outside",
       "people":[{"relation":"Sister","name":"Estelle","phone":"+237670000111"}]}
      """;

  @Test
  void aSeekerKeepsTheirOwnSealedPlan() throws Exception {
    String seeker = seeker("+237670009101");
    mvc.perform(get("/v1/me/safety-plan").headers(bearer(seeker)))
        .andExpect(status().isNoContent());

    mvc.perform(
            put("/v1/me/safety-plan")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PLAN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warningSigns").value("Not sleeping, skipping meals"))
        .andExpect(jsonPath("$.people[0].name").value("Estelle"))
        .andExpect(jsonPath("$.madeWith").doesNotExist());
    mvc.perform(get("/v1/me/safety-plan").headers(bearer(seeker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calming").value("A walk outside"));

    // At rest it is sealed: none of the seeker's words are in the table.
    byte[] sealed = jdbc.queryForObject("SELECT sealed FROM safety_plan LIMIT 1", byte[].class);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("Estelle");

    mvc.perform(delete("/v1/me/safety-plan").headers(bearer(seeker)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/v1/me/safety-plan").headers(bearer(seeker)))
        .andExpect(status().isNoContent());
  }

  @Test
  void onlyABookedSpecialistCanBeNamedAndSpecialistsHaveNoPlan() throws Exception {
    String seeker = seeker("+237670009102");
    Applicant specialist =
        approvedSpecialist("+237696000309", "Plan Helper", staff("ops-sp", "P", "OPERATIONS"));
    mvc.perform(
            put("/v1/me/safety-plan")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    PLAN.replace(
                        "\"people\"",
                        "\"madeWithSpecialistId\":\"" + specialist.id() + "\",\"people\"")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/me/safety-plan").headers(bearer(specialist.token())))
        .andExpect(status().isForbidden());
  }

  private String seeker(String phone) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Planner",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }
}
