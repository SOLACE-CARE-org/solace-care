package care.solace.core.identity;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Keycloak's admin REST API, called with the members realm's {@code core-admin} client, whose
 * service account can only manage users (infra/keycloak/realms/solace-members-realm.json).
 */
@Component
class KeycloakAdminMembers implements KeycloakMembers {

  static final String PHONE_ATTRIBUTE = "phone_number";

  private final RestClient http;
  private final String users;

  KeycloakAdminMembers(
      IdentitySettings settings,
      ClientRegistrationRepository registrations,
      OAuth2AuthorizedClientService clients,
      RestClient.Builder builder) {
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    String registration = settings.keycloak().clientRegistration();
    var timeouts = new SimpleClientHttpRequestFactory();
    // Placeholders, not in the specs: a sign-up should not hang on a slow Keycloak.
    timeouts.setConnectTimeout(2_000);
    timeouts.setReadTimeout(5_000);
    this.http =
        builder
            .clone()
            .baseUrl(settings.keycloak().adminUrl())
            .requestFactory(timeouts)
            .requestInterceptor(
                (request, body, execution) -> {
                  OAuth2AuthorizedClient client =
                      manager.authorize(
                          OAuth2AuthorizeRequest.withClientRegistrationId(registration)
                              .principal(registration)
                              .build());
                  if (client == null) {
                    throw new IllegalStateException("No Keycloak admin token");
                  }
                  request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
                  return execution.execute(request, body);
                })
            .build();
    this.users = "/admin/realms/" + settings.keycloak().realm() + "/users";
  }

  @Override
  public String create(UUID accountId, Account.Type type, String phone, String email) {
    URI location;
    try {
      location =
          http.post()
              .uri(users)
              .body(representation(accountId.toString(), phone, email))
              .retrieve()
              .toBodilessEntity()
              .getHeaders()
              .getLocation();
    } catch (HttpClientErrorException.Conflict conflict) {
      if (email != null) {
        throw new EmailTaken();
      }
      throw conflict;
    }
    if (location == null) {
      throw new IllegalStateException("Keycloak did not return the new user's location");
    }
    String path = location.getPath();
    String keycloakId = path.substring(path.lastIndexOf('/') + 1);
    // The roles the user could be given: readable with view-users, so core-admin needs no
    // view-realm right.
    List<?> available =
        http.get()
            .uri(users + "/{id}/role-mappings/realm/available", keycloakId)
            .retrieve()
            .body(List.class);
    Object role =
        (available == null ? List.of() : available)
            .stream()
                .filter(r -> r instanceof Map<?, ?> m && type.name().equals(m.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Realm role " + type + " missing"));
    http.post()
        .uri(users + "/{id}/role-mappings/realm", keycloakId)
        .body(List.of(role))
        .retrieve()
        .toBodilessEntity();
    return keycloakId;
  }

  @Override
  public void updateContacts(String keycloakId, String phone, String email) {
    try {
      http.put()
          .uri(users + "/{id}", keycloakId)
          .body(representation(null, phone, email))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.Conflict conflict) {
      throw new EmailTaken();
    }
  }

  @Override
  public void delete(String keycloakId) {
    try {
      http.delete().uri(users + "/{id}", keycloakId).retrieve().toBodilessEntity();
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
        throw e;
      }
    }
  }

  private static Map<String, Object> representation(String username, String phone, String email) {
    Map<String, Object> user = new HashMap<>();
    if (username != null) {
      user.put("username", username);
    }
    user.put("enabled", true);
    user.put("email", email);
    user.put("emailVerified", false);
    user.put("attributes", phone == null ? Map.of() : Map.of(PHONE_ATTRIBUTE, List.of(phone)));
    return user;
  }
}
