package care.solace.core.notifications;

import java.util.Locale;

/**
 * Sends sign-in codes with the "Sign-in or sign-up code" wording in notification-copy.md. Keycloak
 * checks the codes; Core only words and sends them (CLAUDE.md, Sign-in).
 */
public interface SignInCodes {

  /**
   * Sends {@code code}, valid for {@code minutes}, to a phone number (E.164) or an email address.
   */
  void send(Channel channel, String to, String code, int minutes, Locale locale);

  /** How the code travels. */
  enum Channel {
    SMS,
    EMAIL
  }
}
