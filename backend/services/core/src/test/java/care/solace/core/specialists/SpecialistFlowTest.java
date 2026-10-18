package care.solace.core.specialists;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Specialists made the way the app and the portal make them, for tests of what comes after sign-up:
 * applicants who submitted, applications fully verified, approved specialists.
 */
public abstract class SpecialistFlowTest extends MemberStackTest {

  protected static final byte[] PDF = "%PDF-1.7 1 0 obj".getBytes(StandardCharsets.US_ASCII);
  protected static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
  protected static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcTemplate jdbc;

  // --- Helpers ---------------------------------------------------------------------------------

  public record Applicant(String id, String token) {}

  /** A specialist who went through every step in the app and submitted. */
  protected Applicant submittedApplicant(String phone, String name) throws Exception {
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
    String token = signIn(phone);
    String id = json(mvc.perform(get("/v1/me").headers(bearer(token)))).path("id").asText();
    step(
        token,
        "/practice",
        """
        {"profession":"clinical-psychologist","licenceNumber":"ONPC-%s","yearsInPractice":8,
         "licensingBody":"Ordre national","focusAreas":["anxiety"],"bio":"I help adults with anxiety."}
        """
            .formatted(phone.substring(8)));
    step(
        token,
        "/languages",
        "{\"languages\":[{\"code\":\"en\",\"proficiency\":\"NATIVE\"},{\"code\":\"fr\",\"proficiency\":\"FLUENT\"}]}");
    upload(token, "LICENCE", new MockMultipartFile("file", "licence.pdf", "x", PDF))
        .andExpect(status().isOk());
    upload(
            token,
            "IDENTITY",
            new MockMultipartFile("file", "front.png", "x", PNG),
            new MockMultipartFile("file", "back.png", "x", PNG))
        .andExpect(status().isOk());
    upload(token, "PHOTO", new MockMultipartFile("file", "me.jpg", "x", JPEG))
        .andExpect(status().isOk());
    step(
        token,
        "/services",
        "{\"offerings\":[{\"modality\":\"AUDIO\",\"enabled\":true,\"durationMinutes\":50,\"price\":15000}]}");
    mvc.perform(
            put("/v1/me/weekly-hours")
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timeZone":"Africa/Douala","breakMinutes":10,"minimumNoticeHours":2,
                     "days":[{"weekday":"MONDAY","ranges":[{"start":"09:00","end":"12:00"}]}]}
                    """))
        .andExpect(status().isOk());
    step(
        token,
        "/payout",
        "{\"method\":\"MTN_MOMO\",\"phone\":\"%s\",\"holderName\":\"%s\"}".formatted(phone, name));
    mvc.perform(post("/v1/me/specialist-application/submission").headers(bearer(token)))
        .andExpect(status().isOk());
    return new Applicant(id, token);
  }

  protected Applicant approvedSpecialist(String phone, String name, RequestPostProcessor ops)
      throws Exception {
    Applicant applicant = fullyVerified(phone, name, ops);
    act(post(app(applicant, "/approval-proposal")).with(ops)).andExpect(status().isOk());
    act(post(app(applicant, "/approval")).with(staff("second", "Second Approver", "OPERATIONS")))
        .andExpect(jsonPath("$.status").value("APPROVED"));
    return applicant;
  }

  /** Opened for review by {@code ops}, with all six items verified. */
  protected Applicant fullyVerified(String phone, String name, RequestPostProcessor ops)
      throws Exception {
    Applicant applicant = submittedApplicant(phone, name);
    act(post(app(applicant, "/review")).with(ops)).andExpect(status().isOk());
    item(applicant, ops, "IDENTITY", "{\"verified\":true}");
    item(
        applicant,
        ops,
        "LICENCE",
        "{\"verified\":true,\"licenceValidUntil\":\"%s\"}".formatted(LocalDate.now().plusYears(1)));
    act(
        put(app(applicant, "/bio"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"language\":\"fr\",\"text\":\"Bio en français.\"}"));
    item(applicant, ops, "PROFILE", "{\"verified\":true}");
    item(applicant, ops, "PAYOUT", "{\"verified\":true}");
    language(applicant, ops, "en", "NATIVE");
    language(applicant, ops, "fr", "FLUENT").andExpect(jsonPath("$.verifiedItems").value(6));
    return applicant;
  }

  protected void step(String token, String step, String body) throws Exception {
    mvc.perform(
            put("/v1/me/specialist-application" + step)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  protected ResultActions upload(String token, String kind, MockMultipartFile... files)
      throws Exception {
    var request = multipart(HttpMethod.PUT, "/v1/me/specialist-application/documents/" + kind);
    for (MockMultipartFile f : files) {
      request.file(f);
    }
    return mvc.perform(request.headers(bearer(token)));
  }

  protected ResultActions item(Applicant a, RequestPostProcessor staff, String item, String body)
      throws Exception {
    return act(
        put(app(a, "/items/" + item))
            .with(staff)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  protected ResultActions language(
      Applicant a, RequestPostProcessor staff, String code, String level) throws Exception {
    return act(
        put(app(a, "/languages/" + code))
            .with(staff)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"verifiedLevel\":\"" + level + "\"}"));
  }

  protected ResultActions act(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    return mvc.perform(request);
  }

  protected static String app(Applicant a, String rest) {
    return "/admin/v1/specialist-applications/" + a.id() + rest;
  }

  /** A staff member's token from the staff realm, as the admin portal would send it. */
  protected static RequestPostProcessor staff(String id, String name, String... roles) {
    String issuer = KEYCLOAK.getAuthServerUrl().replaceAll("/$", "") + "/realms/solace-staff";
    return jwt()
        .jwt(
            token ->
                token
                    .issuer(issuer)
                    .subject(id)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  protected static HttpHeaders bearer(String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  protected static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(
        result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
