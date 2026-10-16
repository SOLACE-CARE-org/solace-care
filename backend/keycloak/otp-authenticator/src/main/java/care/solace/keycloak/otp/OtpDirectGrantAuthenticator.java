package care.solace.keycloak.otp;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;

/**
 * One-time code sign-in for seekers and specialists, on the app's own screens (D6).
 *
 * <p>The app calls the token endpoint twice with {@code grant_type=password}:
 *
 * <ol>
 *   <li>With {@code username} (phone in E.164 or email) and no {@code code}: a code is sent if the
 *       contact belongs to an enabled account. The answer is the same either way, {@code 401
 *       code_required}, so nobody can find out which contacts have accounts.
 *   <li>With {@code username} and {@code code}: tokens if the code is right, otherwise {@code 401
 *       invalid_grant}.
 * </ol>
 */
public final class OtpDirectGrantAuthenticator implements Authenticator {

  static final String USERNAME_PARAM = "username";
  static final String CODE_PARAM = "code";
  static final String PHONE_ATTRIBUTE = "phone_number";
  static final String LOCALE_ATTRIBUTE = "locale";

  private final Clock clock;

  OtpDirectGrantAuthenticator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    Contact contact = Contact.parse(form.getFirst(USERNAME_PARAM));
    if (contact == null) {
      context.getEvent().error(Errors.USER_NOT_FOUND);
      context.failure(
          AuthenticationFlowError.INVALID_USER,
          error(
              400, "invalid_request", "username must be a phone number in E.164 form or an email"));
      return;
    }

    OtpSettings settings = OtpSettings.from(config(context.getAuthenticatorConfig()));
    CodeBook codes =
        new CodeBook(
            new SingleUseStore(context.getSession()), settings, clock, context.getRealm().getId());
    UserModel user = findUser(context.getSession(), context.getRealm(), contact);
    String code = form.getFirst(CODE_PARAM);

    if (code == null || code.isBlank()) {
      if (user != null && user.isEnabled()) {
        Optional<String> issued = codes.issue(user.getId());
        if (issued.isPresent()) {
          CodeSender.forId(settings.sender())
              .send(contact, issued.get(), user.getFirstAttribute(LOCALE_ATTRIBUTE));
        }
      }
      context.challenge(
          error(401, "code_required", "A code was sent if this contact belongs to an account"));
      return;
    }

    if (user == null || !user.isEnabled()) {
      context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
      context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, invalidCode());
      return;
    }
    context.setUser(user);
    switch (codes.verify(user.getId(), code.trim())) {
      case ACCEPTED -> context.success();
      case WRONG, NO_LIVE_CODE -> {
        context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
        context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, invalidCode());
      }
    }
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    // Direct grant has no second form step.
  }

  @Override
  public boolean requiresUser() {
    return false;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

  @Override
  public void close() {}

  private static UserModel findUser(KeycloakSession session, RealmModel realm, Contact contact) {
    return switch (contact.kind()) {
      case EMAIL -> session.users().getUserByEmail(realm, contact.value());
      case PHONE ->
          session
              .users()
              .searchForUserByUserAttributeStream(realm, PHONE_ATTRIBUTE, contact.value())
              .findFirst()
              .orElse(null);
    };
  }

  private static Map<String, String> config(AuthenticatorConfigModel model) {
    return model == null ? null : model.getConfig();
  }

  private static Response invalidCode() {
    return error(401, "invalid_grant", "Invalid or expired code");
  }

  private static Response error(int status, String error, String description) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(Map.of("error", error, "error_description", description))
        .build();
  }

  /** Keycloak's cluster-wide single-use object store. */
  private record SingleUseStore(KeycloakSession session) implements CodeBook.Store {

    private SingleUseObjectProvider provider() {
      return session.singleUseObjects();
    }

    @Override
    public Map<String, String> get(String key) {
      return provider().get(key);
    }

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
      provider().put(key, lifespanSeconds, notes);
    }

    @Override
    public void replace(String key, Map<String, String> notes) {
      provider().replace(key, notes);
    }

    @Override
    public void remove(String key) {
      provider().remove(key);
    }
  }
}
