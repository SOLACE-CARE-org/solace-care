package care.solace.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.MemberStackTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seeker sign-up end to end, the way the app does it: Core creates the account, Keycloak (real
 * realms, real code authenticator) sends and checks the code, and the first call to {@code /v1/me}
 * activates the account and publishes {@code core.user.registered}.
 */
class SignupFlowIntegrationTest extends MemberStackTest {

  private static final String ADULT = LocalDate.now().minusYears(30).toString();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired UnverifiedSignups unverified;

  // --- Sign-up -------------------------------------------------------------------------------

  @Test
  void seekerSignsUpVerifiesTheirPhoneAndFinishesSignUp() throws Exception {
    String phone = "+237670100001";
    signUp("Mireille", phone, "mireille@example.cm");

    String token = signIn(phone);
    JsonNode me = me(token);
    assertThat(me.path("role").asText()).isEqualTo("SEEKER");
    assertThat(me.path("displayName").asText()).isEqualTo("Mireille");
    assertThat(me.path("appLanguage").asText()).isEqualTo("en");
    assertThat(me.path("onboarding").path("preferences").asBoolean()).isFalse();
    assertThat(me.path("onboarding").path("privacy").asBoolean()).isFalse();
    String id = me.path("id").asText();

    String registered = firstEventOn("core.user", id, "core.user.registered");
    assertThat(registered)
        .contains("\"type\":\"core.user.registered\"")
        .contains("\"accountType\":\"SEEKER\"")
        .doesNotContain(phone)
        .doesNotContain("Mireille")
        .doesNotContain("mireille@");

    // Step 2: Make it yours.
    mvc.perform(
            put("/v1/me/preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"appLanguage":"fr","sessionLanguages":["en","fr"],
                     "helpTopics":["sleep","anxiety"],"specialistGender":"WOMAN"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appLanguage").value("fr"))
        .andExpect(jsonPath("$.helpTopics.length()").value(2))
        .andExpect(jsonPath("$.specialistGender").value("WOMAN"));

    // Step 3: privacy choices. Phase 1 offers AI assistance and AI session notes only.
    mvc.perform(get("/v1/me/consents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consents.length()").value(2))
        .andExpect(jsonPath("$.consents[0].purpose").value("AI_ASSIST"))
        .andExpect(jsonPath("$.consents[0].granted").value(false))
        .andExpect(jsonPath("$.consents[0].version").value("v1"));
    mvc.perform(
            put("/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"choices":[{"purpose":"AI_ASSIST","granted":true},
                                {"purpose":"AI_SCRIBE","granted":false}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consents[0].granted").value(true))
        .andExpect(jsonPath("$.consents[1].granted").value(false));
    assertThat(firstEventOn("core.consent", id, "\"purpose\":\"AI_ASSIST\""))
        .contains("\"purpose\":\"AI_ASSIST\"")
        .contains("\"granted\":true")
        .contains("\"locale\":\"fr\"");

    // A purpose from a later phase is refused.
    mvc.perform(
            put("/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choices\":[{\"purpose\":\"TRACKING\",\"granted\":true}]}"))
        .andExpect(status().isBadRequest());

    JsonNode after = me(token);
    assertThat(after.path("appLanguage").asText()).isEqualTo("fr");
    assertThat(after.path("onboarding").path("preferences").asBoolean()).isTrue();
    assertThat(after.path("onboarding").path("privacy").asBoolean()).isTrue();
  }

  @Test
  void signingUpAgainWithARegisteredNumberLooksTheSameAndChangesNothing() throws Exception {
    String phone = "+237670100002";
    signUp("Awa", phone, null);
    String token = signIn(phone);
    me(token);

    signUp("Someone else", phone, null);
    assertThat(me(token).path("displayName").asText()).isEqualTo("Awa");
  }

  @Test
  void anUnverifiedSignUpCanBeRedoneWithNewDetails() throws Exception {
    String phone = "+237670100003";
    signUp("Typo", phone, null);
    signUp("Paul", phone, null);
    assertThat(me(signIn(phone)).path("displayName").asText()).isEqualTo("Paul");
  }

  @Test
  void underEighteensAndOutdatedTermsAreRefused() throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                .content(
                    signupBody(
                        "Junior",
                        "+237670100004",
                        null,
                        LocalDate.now().minusYears(17).toString(),
                        "v1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.too-young"))
        .andExpect(
            jsonPath("$.title").value("Vous devez avoir 18 ans ou plus pour utiliser Solace."));
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody("Old app", "+237670100005", null, ADULT, "v0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.outdated-terms"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE phone IN ('+237670100004', '+237670100005')",
                Integer.class))
        .isZero();
  }

  @Test
  void choicesMustComeFromTheCatalogue() throws Exception {
    String phone = "+237670100006";
    signUp("Rose", phone, null);
    String token = signIn(phone);
    mvc.perform(
            put("/v1/me/preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"appLanguage":"en","sessionLanguages":["xx"],"helpTopics":[],
                     "specialistGender":"ANY"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("account.unknown-choice"));
  }

  // --- Which contact a sign-in proved ---------------------------------------------------------

  @Test
  void onlyTheContactTheCodeWentToCountsAsVerified() throws Exception {
    String phone = "+237670100007";
    String email = "verify.me@example.cm";
    signUp("Ngono", phone, email);

    me(signIn(email));
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, phone_verified_at, email_verified_at FROM user_account WHERE phone = ?",
            phone);
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("email_verified_at")).isNotNull();
    // The phone number was only typed in: no guest booking may be claimed through it yet.
    assertThat(row.get("phone_verified_at")).isNull();

    me(signIn(phone));
    assertThat(
            jdbc.queryForObject(
                "SELECT phone_verified_at FROM user_account WHERE phone = ?", Object.class, phone))
        .isNotNull();
  }

  // --- Who may call what ----------------------------------------------------------------------

  @Test
  void serviceAndStaffTokensAreNotMembers() throws Exception {
    String service = serviceToken("tracking-service", "tracking-service-dev-secret");
    mvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + service))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("account.not-a-member"));
  }

  @Test
  void onlyKeycloakMayHandOverCodesToSend() throws Exception {
    String body =
        """
        {"channel":"SMS","to":"+237670100008","code":"482913","expiresInMinutes":10,"locale":"fr"}
        """;
    String keycloak = serviceToken("keycloak-code-sender", "keycloak-code-sender-dev-secret");
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + keycloak)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    String tracking = serviceToken("tracking-service", "tracking-service-dev-secret");
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tracking)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/internal/v1/sign-in-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void catalogueIsOpenAndInTheCallersLanguage() throws Exception {
    mvc.perform(get("/v1/catalogue/session-languages").header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("en"))
        .andExpect(jsonPath("$[0].name").value("Anglais"))
        .andExpect(jsonPath("$[1].name").value("Français"));
    mvc.perform(get("/v1/catalogue/help-topics").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(8))
        .andExpect(jsonPath("$[5].label").value("Women's health"));
  }

  // --- Unverified accounts expire --------------------------------------------------------------

  @Test
  void unverifiedAccountsAreRemovedWithTheirKeycloakUser() throws Exception {
    String phone = "+237670100009";
    signUp("Never came back", phone, null);
    String keycloakId =
        jdbc.queryForObject(
            "SELECT keycloak_id FROM user_account WHERE phone = ?", String.class, phone);
    jdbc.update(
        "UPDATE user_account SET created_at = ? WHERE phone = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))),
        phone);

    unverified.removeExpired();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE phone = ?", Integer.class, phone))
        .isZero();
    assertThat(
            KEYCLOAK
                .getKeycloakAdminClient()
                .realm("solace-members")
                .users()
                .search(phone, 0, 10)
                .stream()
                .filter(u -> keycloakId.equals(u.getId())))
        .isEmpty();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private void signUp(String name, String phone, String email) throws Exception {
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(name, phone, email, ADULT, "v1")))
        .andExpect(status().isAccepted());
  }

  private static String signupBody(
      String name, String phone, String email, String dateOfBirth, String terms) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("displayName", name);
    body.put("phone", phone);
    if (email != null) {
      body.put("email", email);
    }
    body.put("dateOfBirth", dateOfBirth);
    body.put("termsVersion", terms);
    body.put("privacyNoticeVersion", "v1");
    body.put("appLanguage", "en");
    return JSON.writeValueAsString(body);
  }

  private JsonNode me(String token) throws Exception {
    String body =
        mvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JSON.readTree(body);
  }
}
