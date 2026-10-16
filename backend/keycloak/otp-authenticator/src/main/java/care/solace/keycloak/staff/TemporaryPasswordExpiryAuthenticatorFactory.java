package care.solace.keycloak.staff;

import java.time.Clock;
import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Registers {@link TemporaryPasswordExpiryAuthenticator} with Keycloak. */
public final class TemporaryPasswordExpiryAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "solace-temporary-password-expiry";

  private static final TemporaryPasswordExpiryAuthenticator INSTANCE =
      new TemporaryPasswordExpiryAuthenticator(Clock.systemUTC());

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Solace temporary password expiry";
  }

  @Override
  public String getHelpText() {
    return "Refuses a temporary password more than 72 hours old.";
  }

  @Override
  public String getReferenceCategory() {
    return "password";
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }

  @Override
  public Requirement[] getRequirementChoices() {
    return new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED};
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }

  @Override
  public Authenticator create(KeycloakSession session) {
    return INSTANCE;
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}
}
