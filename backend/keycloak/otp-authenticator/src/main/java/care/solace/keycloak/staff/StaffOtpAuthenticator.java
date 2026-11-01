package care.solace.keycloak.staff;

import care.solace.keycloak.otp.TestCodes;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Keycloak's authenticator code step, which also takes the default test code while it is on (D63).
 * In that time an account needs no authenticator set up. With the code off, this is Keycloak's own
 * step, and an account without an authenticator is sent to set one up.
 */
public final class StaffOtpAuthenticator extends OTPFormAuthenticator {

  private static final String CODE_PARAM = "otp";

  @Override
  public void validateOTP(AuthenticationFlowContext context) {
    String code = context.getHttpRequest().getDecodedFormParameters().getFirst(CODE_PARAM);
    if (TestCodes.acceptsStaff(code)) {
      context.success();
      return;
    }
    super.validateOTP(context);
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return TestCodes.staffOn() || super.configuredFor(session, realm, user);
  }
}
