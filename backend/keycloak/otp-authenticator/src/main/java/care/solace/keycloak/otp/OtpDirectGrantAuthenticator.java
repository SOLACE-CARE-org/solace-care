package care.solace.keycloak.otp;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 *       invalid_grant} with {@code attempts_left}; after the last wrong try a new code is sent and
 *       the answer says {@code new_code_sent}; an expired or used code says {@code code_expired}.
 * </ol>
 *
 * Contacts without an account get exactly the same answers (a decoy code is tracked for them and
 * never sent), and every wrong code counts towards the realm's brute-force lockout.
 */
public final class OtpDirectGrantAuthenticator implements Authenticator {

  static final String USERNAME_PARAM = "username";
  static final String CODE_PARAM = "code";
  static final String PHONE_ATTRIBUTE = "phone_number";
  static final String LOCALE_ATTRIBUTE = "locale";
  static final String VERIFIED_CONTACT_NOTE = "solace.verified_contact";
  static final String DECOY_PREFIX = "no-account:";

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
    UserModel found = findUser(context.getSession(), context.getRealm(), contact);
    UserModel user = found != null && found.isEnabled() ? found : null;
    // A contact with no account gets a decoy code that is tracked the same way but never sent, so
    // every answer below is the same whether or not the contact has an account.
    String holder = user != null ? user.getId() : DECOY_PREFIX + contact.value();
    String code = form.getFirst(CODE_PARAM);

    if (code == null || code.isBlank()) {
      issueAndSend(codes, holder, user, contact, settings);
      context.challenge(
          error(401, "code_required", "A code was sent if this contact belongs to an account"));
      return;
    }

    CodeBook.Check check =
        user != null && TestCodes.acceptsSignIn(code.trim())
            ? new CodeBook.Check(CodeBook.Result.ACCEPTED, 0)
            : codes.verify(holder, code.trim());
    if (check.result() == CodeBook.Result.ACCEPTED && user != null) {
      context.setUser(user);
      // Tells Core which contact this sign-in proved (token claim verified_contact), so guest
      // bookings are only ever claimed through a contact the person controls.
      context
          .getAuthenticationSession()
          .setUserSessionNote(
              VERIFIED_CONTACT_NOTE, contact.kind().name().toLowerCase(Locale.ROOT));
      context.success();
      return;
    }
    if (user != null) {
      context.setUser(user);
      context.getEvent().user(user);
    }
    context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "invalid_grant");
    body.put("error_description", "Invalid or expired code");
    switch (check.result()) {
      case WRONG -> body.put("attempts_left", check.attemptsLeft());
      // After the last wrong try a new code goes out (StateCodeWrong: "After [3] wrong tries we'll
      // send a new code").
      case EXHAUSTED -> {
        issueAndSend(codes, holder, user, contact, settings);
        body.put("attempts_left", 0);
        body.put("new_code_sent", true);
      }
      default -> body.put("code_expired", true);
    }
    context.failure(
        AuthenticationFlowError.INVALID_CREDENTIALS,
        Response.status(401).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build());
  }

  private static void issueAndSend(
      CodeBook codes, String holder, UserModel user, Contact contact, OtpSettings settings) {
    Optional<String> issued = codes.issue(holder);
    if (issued.isPresent() && user != null) {
      CodeSender.forId(settings.sender())
          .send(
              contact, issued.get(), user.getFirstAttribute(LOCALE_ATTRIBUTE), settings.codeTtl());
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
