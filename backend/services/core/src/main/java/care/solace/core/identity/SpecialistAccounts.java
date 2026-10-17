package care.solace.core.identity;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specialist accounts for the specialists module's sign-up. Same rules as seeker sign-up ({@link
 * Signups}): an active account is never changed, and the outcome does not say whether the number is
 * registered. Specialists sign in with codes only (D24).
 */
@Service
class SpecialistAccounts implements MemberSignups {

  private final AccountStore store;
  private final KeycloakMembers keycloak;
  private final TransactionTemplate transactions;

  SpecialistAccounts(
      AccountStore store, KeycloakMembers keycloak, TransactionTemplate transactions) {
    this.store = store;
    this.keycloak = keycloak;
    this.transactions = transactions;
  }

  @Override
  public void startSpecialist(Specialist request, Consumer<UUID> profile) {
    String email = normaliseEmail(request.email());

    var existing = store.byPhone(request.phone());
    if (existing.isPresent() && existing.get().status() != Account.Status.PENDING) {
      return;
    }
    if (existing.isPresent() && existing.get().type() != Account.Type.SPECIALIST) {
      // An unverified seeker sign-up on this number: start again as a specialist.
      keycloak.delete(existing.get().keycloakId());
      store.deletePending(existing.get().id());
      existing = Optional.empty();
    }
    UUID id = existing.map(AccountStore.Row::id).orElseGet(UUID::randomUUID);
    if (email != null && store.byEmail(email).filter(other -> !other.id().equals(id)).isPresent()) {
      email = null;
    }

    if (existing.isPresent()) {
      String kept = updateContacts(existing.get().keycloakId(), request.phone(), email);
      transactions.executeWithoutResult(
          s -> {
            store.replacePendingAccount(
                id, kept, request.appLanguage(), request.termsVersion(), null);
            profile.accept(id);
          });
      return;
    }

    String keycloakId;
    String kept = email;
    try {
      keycloakId = keycloak.create(id, Account.Type.SPECIALIST, request.phone(), email);
    } catch (KeycloakMembers.EmailTaken taken) {
      kept = null;
      keycloakId = keycloak.create(id, Account.Type.SPECIALIST, request.phone(), null);
    }
    String finalEmail = kept;
    String finalKeycloakId = keycloakId;
    try {
      transactions.executeWithoutResult(
          s -> {
            store.insertAccount(
                id,
                finalKeycloakId,
                Account.Type.SPECIALIST,
                request.phone(),
                finalEmail,
                request.appLanguage(),
                request.termsVersion(),
                null);
            profile.accept(id);
          });
    } catch (DuplicateKeyException race) {
      // Someone signed up with the same number a moment earlier: theirs stands.
      keycloak.delete(finalKeycloakId);
    } catch (RuntimeException e) {
      keycloak.delete(finalKeycloakId);
      throw e;
    }
  }

  private String updateContacts(String keycloakId, String phone, String email) {
    try {
      keycloak.updateContacts(keycloakId, phone, email);
      return email;
    } catch (KeycloakMembers.EmailTaken taken) {
      keycloak.updateContacts(keycloakId, phone, null);
      return null;
    }
  }

  private static String normaliseEmail(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
