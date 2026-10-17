package care.solace.core.specialists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.MemberStackTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A specialist application end to end, the way the app does it: sign-up without a password (D24), a
 * code sign-in through real Keycloak, the six steps (documents into MinIO), and submission.
 */
class SpecialistApplicationIntegrationTest extends MemberStackTest {

  private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void specialistAppliesStepByStepAndSubmits() throws Exception {
    String phone = "+237699000001";
    signUp("Ngo Ebode Clarisse", phone, "c.ngoebode@example.cm");
    String token = signIn(phone);

    JsonNode me = json(mvc.perform(get("/v1/me").headers(auth(token))).andExpect(status().isOk()));
    assertThat(me.path("role").asText()).isEqualTo("SPECIALIST");
    assertThat(me.path("displayName").asText()).isEqualTo("Ngo Ebode Clarisse");
    assertThat(me.path("applicationStatus").asText()).isEqualTo("DRAFT");

    mvc.perform(get("/v1/me/specialist-application").headers(auth(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.steps.practice").value(false))
        .andExpect(jsonPath("$.pricing.minPrice").value(5000))
        .andExpect(jsonPath("$.pricing.maxPrice").value(20000))
        .andExpect(jsonPath("$.pricing.durations[1]").value(50))
        .andExpect(jsonPath("$.practice").doesNotExist());

    // Step 2: practice.
    mvc.perform(get("/v1/catalogue/professions").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].label").value("Clinical psychologist"));
    putJson(
            token,
            "/practice",
            """
            {"profession":"clinical-psychologist","licenceNumber":"ONPC-1234","yearsInPractice":8,
             "licensingBody":"Ordre national des psychologues","focusAreas":["anxiety","low-mood","grief"],
             "bio":"I help adults through anxiety, low mood and grief."}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps.practice").value(true))
        .andExpect(jsonPath("$.practice.focusAreas.length()").value(3));

    // Step 3: languages.
    putJson(
            token,
            "/languages",
            """
            {"languages":[{"code":"en","proficiency":"NATIVE"},{"code":"fr","proficiency":"FLUENT"}]}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.languages[1].proficiency").value("FLUENT"))
        .andExpect(jsonPath("$.languages[1].verified").value(false));

    // Step 4: documents, into the credentials bucket.
    upload(token, "LICENCE", file("licence-2026.pdf", PDF)).andExpect(status().isOk());
    upload(token, "IDENTITY", file("front.png", PNG), file("back.png", PNG))
        .andExpect(status().isOk());
    upload(token, "PHOTO", file("me.jpg", JPEG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps.documents").value(true));
    String key =
        jdbc.queryForObject(
            "SELECT d.object_key FROM specialist_document d JOIN user_account a ON a.id = d.account_id"
                + " WHERE a.phone = ? AND d.kind = 'LICENCE'",
            String.class,
            phone);
    try (var s3 = s3()) {
      assertThat(s3.getObjectAsBytes(b -> b.bucket("solace-credentials").key(key)).asByteArray())
          .isEqualTo(PDF);
      // Replacing the licence removes the old file.
      upload(token, "LICENCE", file("licence-2027.pdf", PDF)).andExpect(status().isOk());
      assertThat(s3.listObjectsV2(b -> b.bucket("solace-credentials")).contents())
          .noneMatch(o -> o.key().equals(key));
    }

    // Step 5: services.
    putJson(
            token,
            "/services",
            """
            {"offerings":[{"modality":"AUDIO","enabled":true,"durationMinutes":50,"price":15000},
                          {"modality":"VIDEO","enabled":false,"durationMinutes":50,"price":0}]}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps.services").value(true));

    // Step 6: weekly hours.
    mvc.perform(get("/v1/me/weekly-hours").headers(auth(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeZone").value("Africa/Douala"))
        .andExpect(jsonPath("$.days.length()").value(7));
    mvc.perform(
            put("/v1/me/weekly-hours")
                .headers(auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timeZone":"Africa/Douala","breakMinutes":10,"minimumNoticeHours":2,
                     "days":[{"weekday":"MONDAY","ranges":[{"start":"16:00","end":"19:00"},
                                                           {"start":"09:00","end":"12:00"}]},
                             {"weekday":"THURSDAY","ranges":[{"start":"10:00","end":"19:00"}]}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].ranges[0].start").value("09:00"))
        .andExpect(jsonPath("$.days[3].ranges[0].end").value("19:00"))
        .andExpect(jsonPath("$.days[2].ranges.length()").value(0));

    // Step 7: payout; the number comes back masked.
    putJson(
            token,
            "/payout",
            """
            {"method":"MTN_MOMO","phone":"+237699000001","holderName":"Ngo Ebode Clarisse"}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payout.phone").value("+237•••••••01"))
        .andExpect(jsonPath("$.steps.hours").value(true))
        .andExpect(jsonPath("$.steps.payout").value(true));

    mvc.perform(post("/v1/me/specialist-application/submission").headers(auth(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.submittedAt").exists());

    // Submitted: locked until a reviewer asks for changes.
    putJson(
            token,
            "/payout",
            "{\"method\":\"ORANGE_MONEY\",\"phone\":\"+237699000001\",\"holderName\":\"X\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.application-locked"));
    assertThat(
            json(mvc.perform(get("/v1/me").headers(auth(token))))
                .path("applicationStatus")
                .asText())
        .isEqualTo("SUBMITTED");
  }

  @Test
  void anIncompleteApplicationCannotBeSubmitted() throws Exception {
    String phone = "+237699000002";
    signUp("Awa Bello", phone, "awa.bello@example.cm");
    String token = signIn(phone);
    mvc.perform(post("/v1/me/specialist-application/submission").headers(auth(token)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.application-incomplete"));
  }

  @Test
  void eachStepKeepsToItsRules() throws Exception {
    String phone = "+237699000003";
    signUp("Paul Mbarga", phone, "paul.mbarga@example.cm");
    String token = signIn(phone);

    // A profession waiting on the physical-health question is not offered yet (D26).
    putJson(
            token,
            "/practice",
            """
            {"profession":"gynaecologist","licenceNumber":"1","yearsInPractice":3,
             "licensingBody":"Ordre","focusAreas":["anxiety"],"bio":"Bio"}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.unknown-choice"));
    // Up to five areas.
    putJson(
            token,
            "/practice",
            """
            {"profession":"psychiatrist","licenceNumber":"1","yearsInPractice":3,
             "licensingBody":"Ordre","bio":"Bio",
             "focusAreas":["anxiety","low-mood","sleep","grief","relationships","work-stress"]}
            """)
        .andExpect(status().isBadRequest());
    // Prices inside the allowed range, lengths from the list.
    putJson(
            token,
            "/services",
            """
            {"offerings":[{"modality":"AUDIO","enabled":true,"durationMinutes":50,"price":999999}]}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.price-out-of-range"));
    putJson(
            token,
            "/services",
            """
            {"offerings":[{"modality":"VIDEO","enabled":true,"durationMinutes":45,"price":10000}]}
            """)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.price-out-of-range"));
    // Bank transfer waits behind its flag (D26).
    putJson(token, "/payout", "{\"method\":\"BANK_TRANSFER\",\"holderName\":\"Paul Mbarga\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.payout-method-unavailable"));
    // The profile photo must be a photo; anything else must be a PDF, JPEG or PNG.
    upload(token, "PHOTO", file("photo.pdf", PDF))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.document-type"));
    upload(
            token,
            "LICENCE",
            file("licence.docx", "PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.document-type"));
    upload(token, "LICENCE", file("a.pdf", PDF), file("b.pdf", PDF))
        .andExpect(status().isBadRequest());
    byte[] huge = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
    upload(token, "LICENCE", file("huge.pdf", huge))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("request.too-large"));
    // Overlapping hours on one day.
    mvc.perform(
            put("/v1/me/weekly-hours")
                .headers(auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timeZone":"Africa/Douala","breakMinutes":0,"minimumNoticeHours":12,
                     "days":[{"weekday":"FRIDAY","ranges":[{"start":"09:00","end":"13:00"},
                                                           {"start":"12:00","end":"14:00"}]}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("scheduling.overlapping-hours"));
  }

  @Test
  void seekersCannotUseSpecialistEndpoints() throws Exception {
    String phone = "+237699000004";
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"displayName":"Mireille","phone":"+237699000004","dateOfBirth":"%s",
                     "termsVersion":"v1","privacyNoticeVersion":"v1","appLanguage":"en"}
                    """
                        .formatted(LocalDate.now().minusYears(30))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    mvc.perform(get("/v1/me/specialist-application").headers(auth(token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("specialist.not-a-specialist"));
    mvc.perform(get("/v1/me/weekly-hours").headers(auth(token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("specialist.not-a-specialist"));
  }

  @Test
  void anUnverifiedSeekerSignUpOnTheSameNumberBecomesASpecialistApplication() throws Exception {
    String phone = "+237699000005";
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"displayName":"Changed my mind","phone":"+237699000005","dateOfBirth":"%s",
                     "termsVersion":"v1","privacyNoticeVersion":"v1","appLanguage":"fr"}
                    """
                        .formatted(LocalDate.now().minusYears(40))))
        .andExpect(status().isAccepted());
    signUp("Dr Ewane", phone, null);
    JsonNode me = json(mvc.perform(get("/v1/me").headers(auth(signIn(phone)))));
    assertThat(me.path("role").asText()).isEqualTo("SPECIALIST");
    assertThat(me.path("displayName").asText()).isEqualTo("Dr Ewane");
  }

  @Test
  void outdatedTermsAreRefused() throws Exception {
    mvc.perform(
            post("/v1/specialist-signups")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                .content(
                    """
                    {"fullName":"Old app","phone":"+237699000006","email":"old@example.cm",
                     "termsVersion":"v0","codeOfConductVersion":"v1","appLanguage":"en"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("specialist.outdated-terms"));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private void signUp(String name, String phone, String email) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("fullName", name);
    body.put("phone", phone);
    body.put("email", email == null ? phone.substring(1) + "@example.cm" : email);
    body.put("termsVersion", "v1");
    body.put("codeOfConductVersion", "v1");
    body.put("appLanguage", "en");
    mvc.perform(
            post("/v1/specialist-signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andExpect(status().isAccepted());
  }

  private ResultActions putJson(String token, String step, String body) throws Exception {
    return mvc.perform(
        put("/v1/me/specialist-application" + step)
            .headers(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions upload(String token, String kind, MockMultipartFile... files)
      throws Exception {
    var request = multipart(HttpMethod.PUT, "/v1/me/specialist-application/documents/" + kind);
    for (MockMultipartFile f : files) {
      request.file(f);
    }
    return mvc.perform(request.headers(auth(token)));
  }

  private static MockMultipartFile file(String name, byte[] bytes) {
    return new MockMultipartFile("file", name, "application/octet-stream", bytes);
  }

  private static HttpHeaders auth(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(
        result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
