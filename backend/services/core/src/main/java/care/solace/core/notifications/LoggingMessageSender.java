package care.solace.core.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development only: writes messages to the log instead of sending them, so a developer can
 * read a sign-in code. Messages carry codes, so this must never run in a real environment: a
 * LOGGING sender row is used only with {@code solace.notifications.sender=logging}, which only
 * local configuration sets (D61).
 */
class LoggingMessageSender implements SmsSender, EmailSender {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingMessageSender.class);

  @Override
  public void send(String phone, String text) {
    LOG.info("[DEV ONLY] SMS to {}: {}", mask(phone), text);
  }

  @Override
  public void send(String address, String subject, String body) {
    LOG.info("[DEV ONLY] email to {}: {}", mask(address), body);
  }

  /** Keeps the first four and last two characters: +237*******01, as Keycloak masks contacts. */
  static String mask(String contact) {
    if (contact.length() <= 6) {
      return "*".repeat(contact.length());
    }
    return contact.substring(0, 4)
        + "*".repeat(contact.length() - 6)
        + contact.substring(contact.length() - 2);
  }
}
