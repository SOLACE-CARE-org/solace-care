package care.solace.core.identity;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeker sign-up, step 1 (SeekerSignup screen). Creates an unverified account; the code sign-in
 * that follows proves the phone number and activates it ({@link Members}).
 *
 * <p>The outcome is the same whether or not the number already has an account, so sign-up cannot be
 * used to find out who uses Solace. An active account is never changed here.
 */
@Service
class Signups {

  /** "You need to be 18 or over to use Solace." A decision, not a setting (no brackets). */
  static final int MINIMUM_AGE = 18;

  /** Ages are counted in Cameroon's time zone, where the service runs. */
  private static final ZoneId CAMEROON = ZoneId.of("Africa/Douala");

  private final AccountStore store;
  private final KeycloakMembers keycloak;
  private final IdentitySettings settings;
  private final care.solace.core.content.LegalTexts legal;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Signups(
      AccountStore store,
      KeycloakMembers keycloak,
      IdentitySettings settings,
      care.solace.core.content.LegalTexts legal,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.keycloak = keycloak;
    this.settings = settings;
    this.legal = legal;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** What the seeker typed on step 1, already shape-checked against the contract. */
  record Request(
      String displayName,
      String phone,
      String email,
      LocalDate dateOfBirth,
      String termsVersion,
      String privacyNoticeVersion,
      Locale appLanguage) {}

  /** The terms new seekers accept: the published version, else the configured one (D44). */
  String termsVersion() {
    return legal.currentVersion("terms").orElse(settings.termsVersion());
  }

  String privacyNoticeVersion() {
    return legal.currentVersion("privacy-notice").orElse(settings.privacyNoticeVersion());
  }

  void start(Request request) {
    if (!termsVersion().equals(request.termsVersion())
        || !privacyNoticeVersion().equals(request.privacyNoticeVersion())) {
      throw new SolaceException(IdentityError.OUTDATED_TERMS);
    }
    LocalDate today = LocalDate.now(clock.withZone(CAMEROON));
    if (request.dateOfBirth().isAfter(today)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (Period.between(request.dateOfBirth(), today).getYears() < MINIMUM_AGE) {
      throw new SolaceException(IdentityError.TOO_YOUNG);
    }
    String name = request.displayName().strip();
    if (name.isEmpty()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String email = normaliseEmail(request.email());

    var existing = store.byPhone(request.phone());
    if (existing.isPresent() && existing.get().status() != Account.Status.PENDING) {
      return;
    }
    // An email that already belongs to someone else's account is left out rather than refused:
    // refusing would tell the caller the address is registered.
    UUID id = existing.map(AccountStore.Row::id).orElseGet(UUID::randomUUID);
    if (email != null && store.byEmail(email).filter(other -> !other.id().equals(id)).isPresent()) {
      email = null;
    }

    if (existing.isPresent()) {
      String keycloakId = existing.get().keycloakId();
      String kept = updateContacts(keycloakId, request.phone(), email);
      transactions.executeWithoutResult(
          s ->
              store.replacePendingSeeker(
                  id,
                  kept,
                  request.appLanguage(),
                  request.termsVersion(),
                  request.privacyNoticeVersion(),
                  name,
                  request.dateOfBirth()));
      return;
    }

    String keycloakId;
    String kept = email;
    try {
      keycloakId = keycloak.create(id, Account.Type.SEEKER, request.phone(), email);
    } catch (KeycloakMembers.EmailTaken taken) {
      kept = null;
      keycloakId = keycloak.create(id, Account.Type.SEEKER, request.phone(), null);
    }
    String finalEmail = kept;
    String finalKeycloakId = keycloakId;
    try {
      transactions.executeWithoutResult(
          s ->
              store.insertSeeker(
                  id,
                  finalKeycloakId,
                  request.phone(),
                  finalEmail,
                  request.appLanguage(),
                  request.termsVersion(),
                  request.privacyNoticeVersion(),
                  name,
                  request.dateOfBirth()));
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
