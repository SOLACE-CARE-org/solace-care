package care.solace.core.identity;

import java.util.UUID;

/**
 * Member sign-in identities in Keycloak's members realm (D23). Keycloak checks the codes; Core
 * decides who has an account. The username is Core's account id, so tokens carry no phone number.
 */
interface KeycloakMembers {

  /**
   * Creates the sign-in identity with the realm role for {@code type}.
   *
   * @return Keycloak's user id, the {@code sub} of this person's tokens
   */
  String create(UUID accountId, Account.Type type, String phone, String email);

  /** Replaces the phone number and email Keycloak signs this person in with. */
  void updateContacts(String keycloakId, String phone, String email);

  /** Removes the identity; nothing happens if it is already gone. */
  void delete(String keycloakId);

  /** Keycloak refused an email because another identity already uses it. */
  final class EmailTaken extends RuntimeException {
    EmailTaken() {
      super("email already used by another Keycloak user");
    }
  }
}
