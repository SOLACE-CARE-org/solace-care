package care.solace.core.identity;

import care.solace.errors.SolaceException;
import care.solace.events.Events;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The member behind a token, and what the first sign-ins do.
 *
 * <p>Keycloak's code authenticator puts {@code verified_contact} ({@code phone} or {@code email})
 * in the token: the contact the code just went to. The first time each contact is proven this way,
 * Core records it and lets Guest access claim bookings made with it. The first proof of any contact
 * activates the account and publishes {@code core.user.registered}.
 */
@Service
class Members implements Accounts {

  static final String VERIFIED_CONTACT_CLAIM = "verified_contact";

  private final AccountStore store;
  private final IdentitySettings settings;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Members(
      AccountStore store,
      IdentitySettings settings,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.settings = settings;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public Optional<Account> of(Jwt token) {
    return row(token).map(AccountStore.Row::account);
  }

  @Override
  public Account require(Jwt token) {
    return of(token).orElseThrow(() -> new SolaceException(IdentityError.NOT_A_MEMBER));
  }

  @Override
  public Optional<String> seekerDisplayName(UUID accountId) {
    return store.seekerProfile(accountId).map(AccountStore.SeekerProfile::displayName);
  }

  @Override
  public Optional<java.util.Locale> appLanguage(UUID accountId) {
    return store.byId(accountId).map(AccountStore.Row::locale);
  }

  /** The signed-in member, after recording what this sign-in proved. */
  Account signedIn(Jwt token) {
    AccountStore.Row row =
        row(token).orElseThrow(() -> new SolaceException(IdentityError.NOT_A_MEMBER));
    String verified = token.getClaimAsString(VERIFIED_CONTACT_CLAIM);
    if (!"phone".equals(verified) && !"email".equals(verified)) {
      return row.account();
    }
    boolean email = verified.equals("email");
    transactions.executeWithoutResult(
        status -> {
          var now = clock.instant();
          if (store.markContactVerified(row.id(), email, now)) {
            events.publishEvent(
                new ContactVerified(
                    row.id(), email ? null : row.phone(), email ? row.email() : null));
          }
          if (store.activate(row.id(), now)) {
            events.publishEvent(
                Events.envelope(
                    UserRegistered.TYPE,
                    UserRegistered.VERSION,
                    row.id().toString(),
                    row.locale().getLanguage(),
                    new UserRegistered(row.type().name())));
          }
        });
    return store.byKeycloakId(row.keycloakId()).map(AccountStore.Row::account).orElseThrow();
  }

  /** Contacts and details, for identity's own use. */
  Optional<AccountStore.Row> row(Jwt token) {
    if (token == null
        || token.getIssuer() == null
        || !settings.membersIssuer().equals(token.getIssuer().toString())) {
      return Optional.empty();
    }
    return store.byKeycloakId(token.getSubject());
  }
}
