package care.solace.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.utility.MountableFile;

/**
 * Starts Keycloak with the real realm files from infra/keycloak/realms and the built provider jar,
 * then signs in the way the mobile app, the admin portal and the services will.
 */
class RealmsIntegrationTest {

  private static final String MEMBERS = "solace-members";
  private static final String STAFF = "solace-staff";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static KeycloakContainer keycloak;
  private static Keycloak admin;
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    keycloak =
        new KeycloakContainer("quay.io/keycloak/keycloak:26.3.1")
            .withRealmImportFiles("/solace-members-realm.json", "/solace-staff-realm.json")
            .withProviderLibsFrom(List.of(new File(System.getProperty("solace.providerJar"))))
            .withEnv("SOLACE_DEV_CODE_SENDER", "enabled")
            .withCopyFileToContainer(
                MountableFile.forHostPath(System.getProperty("solace.staffTheme")),
                "/opt/keycloak/themes/solace-staff");
    keycloak.start();
    admin = keycloak.getKeycloakAdminClient();
  }

  @AfterAll
  static void stop() {
    if (admin != null) {
      admin.close();
    }
    if (keycloak != null) {
      keycloak.stop();
    }
  }

  // --- Members: one-time code sign-in -----------------------------------------------------------

  @Test
  void memberSignsInWithACodeSentToTheirPhone() throws Exception {
    createMember("seeker-phone", null, "+237670000101", "SEEKER");

    HttpResponse<String> first = memberToken(Map.of("username", "+237 670 000 101"));
    assertThat(first.statusCode()).isEqualTo(401);
    assertThat(json(first).path("error").asText()).isEqualTo("code_required");

    String code = lastCodeFor("+237*******01");
    HttpResponse<String> second = memberToken(Map.of("username", "+237670000101", "code", code));
    assertThat(second.statusCode()).isEqualTo(200);
    JsonNode claims = claims(json(second).path("access_token").asText());
    assertThat(claims.path("realm_access").path("roles").toString()).contains("SEEKER");

    HttpResponse<String> replay = memberToken(Map.of("username", "+237670000101", "code", code));
    assertThat(replay.statusCode()).isEqualTo(401);
    assertThat(json(replay).path("error").asText()).isEqualTo("invalid_grant");
  }

  @Test
  void memberSignsInWithACodeSentToTheirEmail() throws Exception {
    createMember("specialist-email", "dr.mbarga@example.cm", null, "SPECIALIST");

    assertThat(memberToken(Map.of("username", "Dr.Mbarga@example.cm")).statusCode()).isEqualTo(401);
    String code = lastCodeFor("d***@example.cm");

    HttpResponse<String> tokens =
        memberToken(Map.of("username", "dr.mbarga@example.cm", "code", code));
    assertThat(tokens.statusCode()).isEqualTo(200);
    assertThat(claims(json(tokens).path("access_token").asText()).toString())
        .contains("SPECIALIST");
  }

  @Test
  void everyMemberSignInIsLogged() throws Exception {
    createMember("seeker-logged", null, "+237670000131", "SEEKER");
    memberToken(Map.of("username", "+237670000131"));
    String code = lastCodeFor("+237*******31");
    assertThat(memberToken(Map.of("username", "+237670000131", "code", code)).statusCode())
        .isEqualTo(200);

    String userId =
        admin.realm(MEMBERS).users().searchByUsername("seeker-logged", true).get(0).getId();
    assertThat(
            admin
                .realm(MEMBERS)
                .getEvents(List.of("LOGIN"), null, userId, null, null, null, null, null))
        .hasSize(1);
  }

  @Test
  void unknownContactGetsTheSameAnswerAndNoCode() throws Exception {
    String logsBefore = keycloak.getLogs();

    HttpResponse<String> response = memberToken(Map.of("username", "+237699999999"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json(response).path("error").asText()).isEqualTo("code_required");
    assertThat(keycloak.getLogs().substring(logsBefore.length())).doesNotContain("sign-in code");
  }

  @Test
  void tooManyWrongCodesDiscardTheCode() throws Exception {
    createMember("seeker-wrong", null, "+237670000202", "SEEKER");
    memberToken(Map.of("username", "+237670000202"));
    String code = lastCodeFor("+237*******02");
    String wrong = code.equals("000000") ? "111111" : "000000";

    for (int i = 0; i < 5; i++) {
      assertThat(memberToken(Map.of("username", "+237670000202", "code", wrong)).statusCode())
          .isEqualTo(401);
    }
    assertThat(memberToken(Map.of("username", "+237670000202", "code", code)).statusCode())
        .isEqualTo(401);
  }

  @Test
  void codesAndContactsNeverReachTheLogInFull() throws Exception {
    createMember("seeker-logs", "private.person@example.cm", "+237670000303", "SEEKER");
    memberToken(Map.of("username", "+237670000303"));
    memberToken(Map.of("username", "private.person@example.cm"));

    assertThat(keycloak.getLogs()).doesNotContain("670000303").doesNotContain("private.person");
  }

  // --- Staff: realm policy and first sign-in ----------------------------------------------------

  @Test
  void staffRealmFollowsTheStaffPolicy() {
    RealmRepresentation realm = admin.realm(STAFF).toRepresentation();

    assertThat(realm.isRegistrationAllowed()).isFalse();
    assertThat(realm.getSsoSessionIdleTimeout()).isEqualTo(900);
    assertThat(realm.getPasswordPolicy()).contains("length(12)").contains("passwordHistory(5)");
    assertThat(realm.getOtpPolicyType()).isEqualTo("totp");
    assertThat(realm.isBruteForceProtected()).isTrue();
    assertThat(realm.getBrowserFlow()).isEqualTo("solace staff browser");
    assertThat(admin.realm(STAFF).roles().list())
        .extracting(role -> role.getName())
        .contains(
            "SUPER_ADMIN",
            "OPERATIONS",
            "SUPPORT",
            "FINANCE",
            "CLINICAL_REVIEWER",
            "CRISIS_RESPONDER",
            "CONTENT_EDITOR");
  }

  @Test
  void staffFirstSignInForcesANewPasswordThenAnAuthenticatorApp() throws Exception {
    UserRepresentation user = new UserRepresentation();
    user.setUsername("new.staff@solace.care");
    user.setEmail("new.staff@solace.care");
    user.setEnabled(true);
    CredentialRepresentation temporary = new CredentialRepresentation();
    temporary.setType(CredentialRepresentation.PASSWORD);
    temporary.setValue("Temporary-Pass-1234");
    temporary.setTemporary(true);
    user.setCredentials(List.of(temporary));
    admin.realm(STAFF).users().create(user).close();

    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new LocalhostCookieJar())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    String loginPage =
        browser
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            keycloak.getAuthServerUrl()
                                + "/realms/"
                                + STAFF
                                + "/protocol/openid-connect/auth?client_id=solace-admin"
                                + "&response_type=code&scope=openid"
                                + "&redirect_uri=http%3A%2F%2Flocalhost%3A4200%2Fcallback"
                                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                                + "&code_challenge_method=S256"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body();

    String updatePasswordPage =
        submit(
            browser,
            loginPage,
            Map.of("username", "new.staff@solace.care", "password", "Temporary-Pass-1234"));
    assertThat(updatePasswordPage).contains("password-new").contains("1. Choose your password");

    String totpPage =
        submit(
            browser,
            updatePasswordPage,
            Map.of(
                "password-new",
                "My-Own-Strong-Pass-5678",
                "password-confirm",
                "My-Own-Strong-Pass-5678"));
    assertThat(totpPage).contains("totp").contains("2. Add an authenticator app");
  }

  @Test
  void staffSignInUsesTheSolaceThemeInEnglishAndFrench() throws Exception {
    String english = staffLoginPage("en");
    assertThat(english)
        .contains("Staff sign in")
        .contains("Work email")
        .contains("Sessions end after 15 minutes without activity.")
        .contains("generated/tokens.css");

    String french = staffLoginPage("fr");
    assertThat(french).contains("Connexion de l").contains("E-mail professionnel");
  }

  private static String staffLoginPage(String language) throws Exception {
    return HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        keycloak.getAuthServerUrl()
                            + "/realms/"
                            + STAFF
                            + "/protocol/openid-connect/auth?client_id=solace-admin"
                            + "&response_type=code&scope=openid&ui_locales="
                            + language
                            + "&redirect_uri=http%3A%2F%2Flocalhost%3A4200%2Fcallback"
                            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                            + "&code_challenge_method=S256"))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  // --- Services: client credentials with narrow scopes ------------------------------------------

  @Test
  void trackingServiceGetsOnlyItsOwnScopes() throws Exception {
    JsonNode claims = claims(serviceToken("tracking-service", "tracking-service-dev-secret"));

    assertThat(claims.path("scope").asText().split(" "))
        .contains("core:booking-access.read", "core:taxonomy.read", "core:profile-age.read")
        .doesNotContain("tracking:summary.read", "ai:moderation.invoke");
    assertThat(claims.path("aud").toString()).contains("core");
    // Audit and access checks tell services apart from staff by this claim.
    assertThat(claims.path("client_id").asText()).isEqualTo("tracking-service");
  }

  @Test
  void aiServiceCanOnlyReadTrackingSummaries() throws Exception {
    JsonNode claims = claims(serviceToken("ai-service", "ai-service-dev-secret"));

    assertThat(claims.path("scope").asText().split(" "))
        .contains("tracking:summary.read")
        .doesNotContain("core:booking-access.read");
    assertThat(claims.path("aud").toString()).contains("tracking");
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /**
   * Keycloak marks its login cookies Secure, and java.net.CookieManager will not send those over
   * http. Browsers treat localhost as secure, so this jar keeps and sends every cookie, like a
   * browser talking to the test container on localhost.
   */
  private static final class LocalhostCookieJar extends java.net.CookieHandler {
    private final Map<String, String> cookies = new java.util.LinkedHashMap<>();

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
      if (cookies.isEmpty()) {
        return Map.of();
      }
      String header =
          cookies.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining("; "));
      return Map.of("Cookie", List.of(header));
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) {
      responseHeaders.forEach(
          (name, values) -> {
            if ("set-cookie".equalsIgnoreCase(name)) {
              for (String value : values) {
                String pair = value.split(";", 2)[0];
                int eq = pair.indexOf('=');
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
              }
            }
          });
    }
  }

  private static void createMember(String username, String email, String phone, String role) {
    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEmail(email);
    user.setEnabled(true);
    if (phone != null) {
      user.setAttributes(Map.of("phone_number", List.of(phone)));
    }
    admin.realm(MEMBERS).users().create(user).close();
    String id = admin.realm(MEMBERS).users().searchByUsername(username, true).getFirst().getId();
    admin
        .realm(MEMBERS)
        .users()
        .get(id)
        .roles()
        .realmLevel()
        .add(List.of(admin.realm(MEMBERS).roles().get(role).toRepresentation()));
  }

  private static HttpResponse<String> memberToken(Map<String, String> params) throws Exception {
    Map<String, String> form = new java.util.HashMap<>(params);
    form.put("grant_type", "password");
    form.put("client_id", "solace-mobile");
    return post(tokenUrl(MEMBERS), form);
  }

  private static String serviceToken(String clientId, String secret) throws Exception {
    HttpResponse<String> response =
        post(
            tokenUrl(STAFF),
            Map.of(
                "grant_type", "client_credentials",
                "client_id", clientId,
                "client_secret", secret));
    assertThat(response.statusCode()).isEqualTo(200);
    return json(response).path("access_token").asText();
  }

  private static String lastCodeFor(String maskedContact) {
    Matcher matcher =
        Pattern.compile("sign-in code (\\d+) for " + Pattern.quote(maskedContact))
            .matcher(keycloak.getLogs());
    String code = null;
    while (matcher.find()) {
      code = matcher.group(1);
    }
    assertThat(code).as("code logged for %s", maskedContact).isNotNull();
    return code;
  }

  private static String submit(HttpClient browser, String page, Map<String, String> fields)
      throws Exception {
    Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(page);
    assertThat(action.find()).as("form on page").isTrue();
    String url = action.group(1).replace("&amp;", "&");
    return browser
        .send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(fields)))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  private static HttpResponse<String> post(String url, Map<String, String> form) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String tokenUrl(String realm) {
    return keycloak.getAuthServerUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
  }

  private static String encode(Map<String, String> form) {
    return form.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body());
  }

  private static JsonNode claims(String jwt) throws Exception {
    return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }
}
