package care.solace.core.identity;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Starts member accounts for other modules: the specialists module's sign-up (SpecSignup). Identity
 * owns the account and its Keycloak sign-in; the caller owns its profile rows.
 */
public interface MemberSignups {

  /** What a specialist typed on step 1, already shape-checked against the contract. */
  record Specialist(String phone, String email, Locale appLanguage, String termsVersion) {}

  /**
   * Creates an unverified specialist account, or refreshes one that is still unverified. {@code
   * profile} runs in the same transaction with the account id, to create or replace the caller's
   * rows. A still-unverified seeker account on the same number is replaced: nobody has proven the
   * number yet.
   *
   * <p>Nothing happens when the number already belongs to a verified account, and the caller cannot
   * tell, so sign-up cannot be used to find out who uses Solace.
   */
  void startSpecialist(Specialist request, Consumer<UUID> profile);
}
