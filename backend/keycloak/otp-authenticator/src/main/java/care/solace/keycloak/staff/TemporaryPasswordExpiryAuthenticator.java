package care.solace.keycloak.staff;

import java.time.Clock;
import java.time.Instant;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;

/**
 * Runs in the staff browser flow straight after the password check. Refuses the sign-in when the
 * password is a temporary one older than {@link TemporaryPasswordPolicy#VALIDITY}; a Super admin
 * then has to issue a new one.
 */
public final class TemporaryPasswordExpiryAuthenticator implements Authenticator {

  /**
   * Message key in theme-resources/messages; the EN and FR copy comes from the FirstSignIn screen.
   */
  static final String EXPIRED_MESSAGE = "solaceTemporaryPasswordExpired";

  private final Clock clock;

  TemporaryPasswordExpiryAuthenticator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    UserModel user = context.getUser();
    if (user == null) {
      context.attempted();
      return;
    }
    boolean temporary =
        user.getRequiredActionsStream()
            .anyMatch(UserModel.RequiredAction.UPDATE_PASSWORD.name()::equals);
    Instant passwordSetAt =
        user.credentialManager()
            .getStoredCredentialsByTypeStream(PasswordCredentialModel.TYPE)
            .map(CredentialModel::getCreatedDate)
            .filter(created -> created != null)
            .map(Instant::ofEpochMilli)
            .findFirst()
            .orElse(null);

    if (TemporaryPasswordPolicy.isExpired(temporary, passwordSetAt, clock.instant())) {
      context.getEvent().user(user).error(Errors.EXPIRED_CODE);
      context.failureChallenge(
          AuthenticationFlowError.INVALID_CREDENTIALS,
          context.form().setError(EXPIRED_MESSAGE).createLoginUsernamePassword());
      return;
    }
    context.success();
  }

  @Override
  public void action(AuthenticationFlowContext context) {}

  @Override
  public boolean requiresUser() {
    return true;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

  @Override
  public void close() {}
}
