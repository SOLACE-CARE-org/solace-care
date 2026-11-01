package care.solace.core.admin;

import care.solace.errors.SolaceException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Staff accounts in the staff realm, through Keycloak's admin REST API with the realm's {@code
 * core-admin} client (manage-users only). Only a Super admin reaches this, through the Team screen
 * (D7, D23). Passwords are generated here, handed back once, and never stored by Core.
 */
@Component
class KeycloakStaff {

  private static final List<Map<String, Object>> TYPE = List.of();
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
      new ParameterizedTypeReference<>() {};

  /** No 0/O, 1/l/I: the password is read out or copied by hand. */
  private static final String ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-.";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final RestClient http;
  private final String realm;
  private final TestCodes testCodes;

  KeycloakStaff(
      @Value("${solace.identity.keycloak.admin-url:http://localhost:8180}") String adminUrl,
      @Value("${solace.admin.staff-realm:solace-staff}") String realm,
      @Value("${solace.admin.staff-client-registration:keycloak-staff-admin}") String registration,
      ClientRegistrationRepository registrations,
      OAuth2AuthorizedClientService clients,
      RestClient.Builder builder,
      TestCodes testCodes) {
    this.testCodes = testCodes;
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    var timeouts = new SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(2_000);
    timeouts.setReadTimeout(5_000);
    this.realm = "/admin/realms/" + realm;
    this.http =
        builder
            .clone()
            .baseUrl(adminUrl)
            .requestFactory(timeouts)
            .requestInterceptor(
                (request, body, execution) -> {
                  var client =
                      manager.authorize(
                          OAuth2AuthorizeRequest.withClientRegistrationId(registration)
                              .principal(registration)
                              .build());
                  if (client == null) {
                    throw new IllegalStateException("No Keycloak staff admin token");
                  }
                  request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
                  return execution.execute(request, body);
                })
            .build();
  }

  /** 16 characters: meets the realm's length(12) policy with room to spare. */
  static String temporaryPassword() {
    var out = new StringBuilder();
    for (int i = 0; i < 16; i++) {
      out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }

  /**
   * Creates the account with a temporary password and both required actions: a new password and the
   * authenticator app on first sign-in (D7).
   *
   * @return the account's id in the staff realm
   */
  String create(String name, String email, List<String> roles, String password) {
    int space = name.lastIndexOf(' ');
    Map<String, Object> user = new HashMap<>();
    user.put("username", email);
    user.put("email", email);
    user.put("emailVerified", true);
    user.put("enabled", true);
    user.put("firstName", space > 0 ? name.substring(0, space) : name);
    user.put("lastName", space > 0 ? name.substring(space + 1) : "");
    user.put("requiredActions", requiredActions());
    user.put("credentials", List.of(credential(password)));
    URI location;
    try {
      location =
          http.post()
              .uri(realm + "/users")
              .body(user)
              .retrieve()
              .toBodilessEntity()
              .getHeaders()
              .getLocation();
    } catch (HttpClientErrorException.Conflict e) {
      throw new SolaceException(AdminError.STAFF_EMAIL_TAKEN);
    }
    if (location == null) {
      throw new IllegalStateException("Keycloak returned no location for the new staff account");
    }
    String path = location.getPath();
    String id = path.substring(path.lastIndexOf('/') + 1);
    setRoles(id, roles);
    return id;
  }

  /** No authenticator set-up while the staff test code stands in for it (D63). */
  private List<String> requiredActions() {
    return testCodes.staffOn()
        ? List.of("UPDATE_PASSWORD")
        : List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP");
  }

  /** Replaces the account's Solace roles with these. */
  void setRoles(String id, List<String> roles) {
    List<Map<String, Object>> current =
        http.get().uri(realm + "/users/{id}/role-mappings/realm", id).retrieve().body(LIST);
    List<Map<String, Object>> remove = new ArrayList<>();
    for (var r : current == null ? TYPE : current) {
      if (StaffRoles.ALL.contains(String.valueOf(r.get("name")))
          && !roles.contains(String.valueOf(r.get("name")))) {
        remove.add(r);
      }
    }
    List<Map<String, Object>> add = new ArrayList<>();
    for (String role : roles) {
      add.add(http.get().uri(realm + "/roles/{name}", role).retrieve().body(MAP_TYPE));
    }
    if (!remove.isEmpty()) {
      http.method(org.springframework.http.HttpMethod.DELETE)
          .uri(realm + "/users/{id}/role-mappings/realm", id)
          .body(remove)
          .retrieve()
          .toBodilessEntity();
    }
    http.post()
        .uri(realm + "/users/{id}/role-mappings/realm", id)
        .body(add)
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * A new temporary password; the authenticator is set up again too, since a lost password often
   * means a lost phone.
   */
  void resetPassword(String id, String password) {
    http.put()
        .uri(realm + "/users/{id}/reset-password", id)
        .body(credential(password))
        .retrieve()
        .toBodilessEntity();
    List<Map<String, Object>> credentials =
        http.get().uri(realm + "/users/{id}/credentials", id).retrieve().body(LIST);
    for (var c : credentials == null ? TYPE : credentials) {
      if ("otp".equals(c.get("type"))) {
        http.delete()
            .uri(realm + "/users/{id}/credentials/{c}", id, c.get("id"))
            .retrieve()
            .toBodilessEntity();
      }
    }
    http.put()
        .uri(realm + "/users/{id}", id)
        .body(Map.of("requiredActions", requiredActions()))
        .retrieve()
        .toBodilessEntity();
  }

  void disable(String id) {
    http.put()
        .uri(realm + "/users/{id}", id)
        .body(Map.of("enabled", false))
        .retrieve()
        .toBodilessEntity();
  }

  /** Ends every session of the account, so refresh tokens stop working too. */
  void logout(String id) {
    http.post().uri(realm + "/users/{id}/logout", id).retrieve().toBodilessEntity();
  }

  /** True when the authenticator app is set up. */
  boolean hasSecondFactor(String id) {
    try {
      List<Map<String, Object>> credentials =
          http.get().uri(realm + "/users/{id}/credentials", id).retrieve().body(LIST);
      return credentials != null && credentials.stream().anyMatch(c -> "otp".equals(c.get("type")));
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
  }

  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private static Map<String, Object> credential(String password) {
    return Map.of("type", "password", "value", password, "temporary", true);
  }
}
