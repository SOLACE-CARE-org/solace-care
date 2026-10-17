package care.solace.core.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code user_account}, {@code seeker_profile} and the seeker's choices. */
@Repository
class AccountStore {

  private static final String ACCOUNT_COLUMNS =
      "id, keycloak_id, type, status, phone, email, preferred_locale, verified_at, created_at";

  private final JdbcClient db;

  AccountStore(JdbcClient db) {
    this.db = db;
  }

  /** An account row with its contacts, for identity's own use only. */
  record Row(
      UUID id,
      String keycloakId,
      Account.Type type,
      Account.Status status,
      String phone,
      String email,
      Locale locale,
      Instant verifiedAt,
      Instant createdAt) {

    Account account() {
      return new Account(id, type, status, locale, verifiedAt);
    }
  }

  /** The seeker's profile and choices. */
  record SeekerProfile(
      String displayName,
      LocalDate dateOfBirth,
      String specialistGender,
      Instant preferencesSavedAt,
      List<String> sessionLanguages,
      List<String> helpTopics) {}

  Optional<Row> byKeycloakId(String keycloakId) {
    return db.sql("SELECT " + ACCOUNT_COLUMNS + " FROM user_account WHERE keycloak_id = :k")
        .param("k", keycloakId)
        .query(AccountStore::row)
        .optional();
  }

  Optional<Row> byPhone(String phone) {
    return db.sql("SELECT " + ACCOUNT_COLUMNS + " FROM user_account WHERE phone = :p")
        .param("p", phone)
        .query(AccountStore::row)
        .optional();
  }

  Optional<Row> byEmail(String email) {
    return db.sql("SELECT " + ACCOUNT_COLUMNS + " FROM user_account WHERE email = :e")
        .param("e", email)
        .query(AccountStore::row)
        .optional();
  }

  /** A new unverified account; the privacy notice version is null for specialists. */
  void insertAccount(
      UUID id,
      String keycloakId,
      Account.Type type,
      String phone,
      String email,
      Locale locale,
      String termsVersion,
      String privacyNoticeVersion) {
    db.sql(
            """
            INSERT INTO user_account (id, keycloak_id, type, status, phone, email, preferred_locale,
                                      terms_version, privacy_notice_version)
            VALUES (:id, :k, :type, 'PENDING', :p, :e, :l, :t, :pn)
            """)
        .param("id", id)
        .param("k", keycloakId)
        .param("type", type.name())
        .param("p", phone)
        .param("e", email)
        .param("l", locale.getLanguage())
        .param("t", termsVersion)
        .param("pn", privacyNoticeVersion)
        .update();
  }

  /** Replaces the details of a still-unverified account whose owner signs up again. */
  void replacePendingAccount(
      UUID id, String email, Locale locale, String termsVersion, String privacyNoticeVersion) {
    db.sql(
            """
            UPDATE user_account SET email = :e, preferred_locale = :l, terms_version = :t,
                   privacy_notice_version = :pn
             WHERE id = :id AND status = 'PENDING'
            """)
        .param("id", id)
        .param("e", email)
        .param("l", locale.getLanguage())
        .param("t", termsVersion)
        .param("pn", privacyNoticeVersion)
        .update();
  }

  void insertSeeker(
      UUID id,
      String keycloakId,
      String phone,
      String email,
      Locale locale,
      String termsVersion,
      String privacyNoticeVersion,
      String displayName,
      LocalDate dateOfBirth) {
    insertAccount(
        id,
        keycloakId,
        Account.Type.SEEKER,
        phone,
        email,
        locale,
        termsVersion,
        privacyNoticeVersion);
    db.sql(
            """
            INSERT INTO seeker_profile (account_id, display_name, date_of_birth)
            VALUES (:id, :n, :d)
            """)
        .param("id", id)
        .param("n", displayName)
        .param("d", dateOfBirth)
        .update();
    // Until the seeker chooses, the session language is the app language.
    db.sql("INSERT INTO seeker_session_language (account_id, language_code) VALUES (:id, :l)")
        .param("id", id)
        .param("l", locale.getLanguage())
        .update();
  }

  /** Replaces the details of a still-unverified seeker who signs up again. */
  void replacePendingSeeker(
      UUID id,
      String email,
      Locale locale,
      String termsVersion,
      String privacyNoticeVersion,
      String displayName,
      LocalDate dateOfBirth) {
    replacePendingAccount(id, email, locale, termsVersion, privacyNoticeVersion);
    db.sql("UPDATE seeker_profile SET display_name = :n, date_of_birth = :d WHERE account_id = :id")
        .param("id", id)
        .param("n", displayName)
        .param("d", dateOfBirth)
        .update();
  }

  /** PENDING to ACTIVE; true only for the call that made the change. */
  boolean activate(UUID id, Instant now) {
    return db.sql(
                "UPDATE user_account SET status = 'ACTIVE', verified_at = :now"
                    + " WHERE id = :id AND status = 'PENDING'")
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  /**
   * Records the first use of a code sent to the phone ({@code email == false}) or the email; true
   * only for the call that recorded it.
   */
  boolean markContactVerified(UUID id, boolean email, Instant now) {
    String column = email ? "email_verified_at" : "phone_verified_at";
    String contact = email ? "email" : "phone";
    return db.sql(
                "UPDATE user_account SET "
                    + column
                    + " = :now WHERE id = :id AND "
                    + column
                    + " IS NULL AND "
                    + contact
                    + " IS NOT NULL")
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  Optional<SeekerProfile> seekerProfile(UUID id) {
    return db.sql(
            """
            SELECT display_name, date_of_birth, specialist_gender, preferences_saved_at
              FROM seeker_profile WHERE account_id = :id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new SeekerProfile(
                    rs.getString("display_name"),
                    rs.getObject("date_of_birth", LocalDate.class),
                    rs.getString("specialist_gender"),
                    instant(rs, "preferences_saved_at"),
                    codes("seeker_session_language", "language_code", id),
                    codes("seeker_help_topic", "topic_code", id)))
        .optional();
  }

  void savePreferences(
      UUID id,
      Locale locale,
      List<String> sessionLanguages,
      List<String> helpTopics,
      String specialistGender,
      Instant now) {
    db.sql("UPDATE user_account SET preferred_locale = :l WHERE id = :id")
        .param("id", id)
        .param("l", locale.getLanguage())
        .update();
    db.sql(
            "UPDATE seeker_profile SET specialist_gender = :g, preferences_saved_at = :now"
                + " WHERE account_id = :id")
        .param("id", id)
        .param("g", specialistGender)
        .param("now", Timestamp.from(now))
        .update();
    replaceCodes("seeker_session_language", "language_code", id, sessionLanguages);
    replaceCodes("seeker_help_topic", "topic_code", id, helpTopics);
  }

  /** Unverified accounts created before {@code cutoff}, oldest first. */
  List<Row> pendingBefore(Instant cutoff, int limit) {
    return db.sql(
            "SELECT "
                + ACCOUNT_COLUMNS
                + " FROM user_account WHERE status = 'PENDING' AND created_at < :c"
                + " ORDER BY created_at LIMIT :n")
        .param("c", Timestamp.from(cutoff))
        .param("n", limit)
        .query(AccountStore::row)
        .list();
  }

  void deletePending(UUID id) {
    db.sql("DELETE FROM user_account WHERE id = :id AND status = 'PENDING'")
        .param("id", id)
        .update();
  }

  private List<String> codes(String table, String column, UUID id) {
    return db.sql(
            "SELECT " + column + " FROM " + table + " WHERE account_id = :id ORDER BY " + column)
        .param("id", id)
        .query(String.class)
        .list();
  }

  private void replaceCodes(String table, String column, UUID id, List<String> codes) {
    db.sql("DELETE FROM " + table + " WHERE account_id = :id").param("id", id).update();
    for (String code : codes) {
      db.sql("INSERT INTO " + table + " (account_id, " + column + ") VALUES (:id, :c)")
          .param("id", id)
          .param("c", code)
          .update();
    }
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getString("keycloak_id"),
        Account.Type.valueOf(rs.getString("type")),
        Account.Status.valueOf(rs.getString("status")),
        rs.getString("phone"),
        rs.getString("email"),
        Locale.of(rs.getString("preferred_locale")),
        instant(rs, "verified_at"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
