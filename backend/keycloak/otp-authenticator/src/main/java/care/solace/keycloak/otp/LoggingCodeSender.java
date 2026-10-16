package care.solace.keycloak.otp;

import org.jboss.logging.Logger;

/**
 * Local development only: writes the code to the Keycloak log instead of sending it, with the
 * contact masked.
 *
 * <p>Writing codes to a log is never acceptable outside a developer's machine, so this sender
 * refuses to work unless the environment variable {@value #GUARD_VARIABLE} is {@code enabled}. Only
 * infra/docker-compose.yml and the integration tests set it.
 */
public final class LoggingCodeSender implements CodeSender {

  public static final String ID = "logging";
  static final String GUARD_VARIABLE = "SOLACE_DEV_CODE_SENDER";

  private static final Logger LOG = Logger.getLogger(LoggingCodeSender.class);

  @Override
  public void send(Contact contact, String code, String locale) {
    if (!"enabled".equals(System.getenv(GUARD_VARIABLE))) {
      throw new IllegalStateException(
          "The logging code sender is for local development only; set "
              + GUARD_VARIABLE
              + "=enabled to use it");
    }
    LOG.infof("[DEV ONLY] sign-in code %s for %s (%s)", code, contact.masked(), locale);
  }
}
