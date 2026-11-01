package care.solace.keycloak.staff;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticatorFactory;
import org.keycloak.models.KeycloakSession;

/** Registers {@link StaffOtpAuthenticator} with Keycloak. */
public final class StaffOtpAuthenticatorFactory extends OTPFormAuthenticatorFactory {

  public static final String ID = "solace-staff-otp";

  private static final StaffOtpAuthenticator INSTANCE = new StaffOtpAuthenticator();

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Solace staff authenticator code";
  }

  @Override
  public String getHelpText() {
    return "Keycloak's authenticator code form, which also takes the default test code when on.";
  }

  @Override
  public Authenticator create(KeycloakSession session) {
    return INSTANCE;
  }
}
