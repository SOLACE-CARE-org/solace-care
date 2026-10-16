package care.solace.keycloak.otp;

import java.time.Clock;
import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/** Registers {@link OtpDirectGrantAuthenticator} with Keycloak. */
public final class OtpDirectGrantAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "solace-otp-direct-grant";

  private static final OtpDirectGrantAuthenticator INSTANCE =
      new OtpDirectGrantAuthenticator(Clock.systemUTC());

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Solace one-time code (direct grant)";
  }

  @Override
  public String getHelpText() {
    return "Signs a member in with a code sent to their phone or email.";
  }

  @Override
  public String getReferenceCategory() {
    return "otp";
  }

  @Override
  public boolean isConfigurable() {
    return true;
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
    OtpSettings d = OtpSettings.DEFAULTS;
    return ProviderConfigurationBuilder.create()
        .property()
        .name(OtpSettings.CODE_LENGTH)
        .label("Code length")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(Integer.toString(d.codeLength()))
        .add()
        .property()
        .name(OtpSettings.CODE_TTL_SECONDS)
        .label("Code lifetime in seconds")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(Long.toString(d.codeTtl().toSeconds()))
        .add()
        .property()
        .name(OtpSettings.MAX_ATTEMPTS)
        .label("Wrong attempts before the code is discarded")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(Integer.toString(d.maxAttempts()))
        .add()
        .property()
        .name(OtpSettings.RESEND_AFTER_SECONDS)
        .label("Seconds before another code can be sent")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(Long.toString(d.resendAfter().toSeconds()))
        .add()
        .property()
        .name(OtpSettings.SENDER)
        .label("Code sender")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(d.sender())
        .add()
        .build();
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
