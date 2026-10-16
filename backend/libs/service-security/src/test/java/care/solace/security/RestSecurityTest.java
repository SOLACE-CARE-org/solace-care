package care.solace.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = RestSecurityTest.App.class)
@AutoConfigureMockMvc
class RestSecurityTest {

  @Autowired MockMvc mvc;

  @Test
  void healthChecksAreOpen() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void noTokenIsAProblemWithBearerChallenge() throws Exception {
    mvc.perform(get("/me").header("Accept-Language", "fr"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.code").value("auth.required"))
        .andExpect(jsonPath("$.reference").value("AUTH-401"))
        .andExpect(jsonPath("$.title").value("Une connexion est nécessaire."));
  }

  @Test
  void tokenFromAnUntrustedIssuerIsRefusedWithoutCallingIt() throws Exception {
    mvc.perform(
            get("/me")
                .header(
                    "Authorization", "Bearer " + unsignedToken("https://evil.example/realms/x")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.required"));
  }

  @Test
  void realmRolesAndScopesBecomeAuthorities() throws Exception {
    mvc.perform(
            get("/me")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("seeker-1")
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("SEEKER", "offline_access")))
                                    .claim("scope", "profile email"))
                        .authorities(KeycloakAuthorities::authorities)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("seeker-1"))
        .andExpect(jsonPath("$.authorities").value("ROLE_SEEKER,SCOPE_email,SCOPE_profile"));
  }

  @Test
  void missingRoleIsAForbiddenProblem() throws Exception {
    mvc.perform(
            get("/payouts")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token.claim("realm_access", Map.of("roles", List.of("SUPPORT"))))
                        .authorities(KeycloakAuthorities::authorities)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access.denied"))
        .andExpect(jsonPath("$.title").value("No access"));
  }

  private static String unsignedToken(String issuer) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString("{\"alg\":\"none\"}".getBytes())
        + "."
        + b64.encodeToString(("{\"iss\":\"" + issuer + "\",\"sub\":\"x\"}").getBytes())
        + ".";
  }

  @EnableAutoConfiguration
  @EnableMethodSecurity
  @RestController
  static class App {

    @GetMapping("/me")
    Map<String, String> me(Authentication authentication) {
      return Map.of(
          "name",
          authentication.getName(),
          "authorities",
          authentication.getAuthorities().stream()
              .map(Object::toString)
              .sorted()
              .collect(Collectors.joining(",")));
    }

    @GetMapping("/payouts")
    @PreAuthorize("hasRole('FINANCE')")
    String payouts() {
      return "runs";
    }
  }
}
