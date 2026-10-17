package care.solace.keycloak.otp;

import java.time.Duration;

/**
 * Delivers a sign-in code. Core words and sends the real SMS and email ({@link CoreCodeSender});
 * {@link LoggingCodeSender} writes codes to the log on a developer's machine.
 */
public interface CodeSender {

  /**
   * Sends {@code code} to {@code contact}.
   *
   * @param locale the person's app language ({@code en} or {@code fr}) when Keycloak knows it, else
   *     null; Core prefers the account's own language
   * @param lifetime how long the code stays valid, for the message wording
   */
  void send(Contact contact, String code, String locale, Duration lifetime);

  /** Returns the sender with the given id. */
  static CodeSender forId(String id) {
    return switch (id) {
      case LoggingCodeSender.ID -> new LoggingCodeSender();
      case CoreCodeSender.ID -> CoreCodeSender.instance();
      default -> throw new IllegalArgumentException("Unknown code sender: " + id);
    };
  }
}
