package care.solace.keycloak.otp;

/**
 * Delivers a sign-in code. Core sends the real SMS and email with the wording in
 * notification-copy.md; the sender that hands codes to Core arrives with the identity epic in Phase
 * 1. Phase 0 only has {@link LoggingCodeSender}.
 */
public interface CodeSender {

  /**
   * Sends {@code code} to {@code contact}.
   *
   * @param locale the person's app language ({@code en} or {@code fr}), so Core picks the right
   *     copy
   */
  void send(Contact contact, String code, String locale);

  /** Returns the sender with the given id. */
  static CodeSender forId(String id) {
    if (LoggingCodeSender.ID.equals(id)) {
      return new LoggingCodeSender();
    }
    throw new IllegalArgumentException("Unknown code sender: " + id);
  }
}
