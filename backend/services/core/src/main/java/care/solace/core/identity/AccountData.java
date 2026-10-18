package care.solace.core.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The account and the seeker's profile, in the export and in erasure (D40). Erasure leaves a
 * tombstone: the id stays for bookings and the ledger, everything that names or reaches the person
 * goes, and so does their sign-in.
 */
@Component
class AccountData implements PersonalData {

  private final AccountStore store;
  private final JdbcClient db;
  private final KeycloakMembers keycloak;
  private final Clock clock;

  AccountData(AccountStore store, JdbcClient db, KeycloakMembers keycloak, Clock clock) {
    this.store = store;
    this.db = db;
    this.keycloak = keycloak;
    this.clock = clock;
  }

  @Override
  public String section() {
    return "account";
  }

  @Override
  public Object export(UUID accountId) {
    var row = store.byId(accountId).orElse(null);
    if (row == null) {
      return null;
    }
    Map<String, Object> account = new LinkedHashMap<>();
    account.put("id", row.id());
    account.put("type", row.type());
    account.put("phone", row.phone());
    account.put("email", row.email());
    account.put("appLanguage", row.locale().getLanguage());
    account.put("createdAt", row.createdAt());
    store
        .seekerProfile(accountId)
        .ifPresent(
            p -> {
              account.put("displayName", p.displayName());
              account.put("dateOfBirth", p.dateOfBirth());
              account.put("specialistGender", p.specialistGender());
              account.put("sessionLanguages", p.sessionLanguages());
              account.put("helpTopics", p.helpTopics());
            });
    return account;
  }

  @Override
  public void erase(UUID accountId) {
    var row = store.byId(accountId).orElseThrow();
    // The profile and its choices go entirely (their child rows cascade).
    db.sql("DELETE FROM seeker_profile WHERE account_id = :a").param("a", accountId).update();
    db.sql(
            """
            UPDATE user_account SET status = 'ERASED', phone = NULL, email = NULL, keycloak_id = NULL,
                   phone_verified_at = NULL, email_verified_at = NULL, erased_at = :now
             WHERE id = :a
            """)
        .param("a", accountId)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    if (row.keycloakId() != null) {
      keycloak.delete(row.keycloakId());
    }
  }
}
