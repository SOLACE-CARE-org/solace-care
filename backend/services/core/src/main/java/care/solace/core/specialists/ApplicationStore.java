package care.solace.core.specialists;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code specialist_profile} and the application's other rows: focus areas, languages, offerings
 * and payout details.
 */
@Repository
class ApplicationStore {

  private final JdbcClient db;

  ApplicationStore(JdbcClient db) {
    this.db = db;
  }

  record Profile(
      UUID accountId,
      ApplicationStatus status,
      String fullName,
      String profession,
      String licenceNumber,
      Integer yearsExperience,
      String licensingBody,
      String bioEn,
      String bioFr,
      String bioLanguage,
      Instant practiceSavedAt,
      Instant languagesSavedAt,
      Instant servicesSavedAt,
      Instant submittedAt,
      Instant interviewAt,
      String reviewNote) {

    /** The bio in the language it was written in. */
    String bio() {
      return "fr".equals(bioLanguage) ? bioFr : bioEn;
    }
  }

  record Language(String code, String proficiency, Instant verifiedAt) {}

  record Offering(String modality, boolean enabled, int durationMinutes, long price) {}

  record Payout(String method, String phone, String holderName) {}

  /** Creates the DRAFT application, or refreshes a draft whose owner signs up again. */
  void upsertDraft(UUID accountId, String fullName, String codeOfConductVersion) {
    db.sql(
            """
            INSERT INTO specialist_profile (account_id, full_name, code_of_conduct_version)
            VALUES (:id, :n, :c)
            ON CONFLICT (account_id) DO UPDATE
               SET full_name = EXCLUDED.full_name,
                   code_of_conduct_version = EXCLUDED.code_of_conduct_version,
                   updated_at = now()
             WHERE specialist_profile.verification_status = 'DRAFT'
            """)
        .param("id", accountId)
        .param("n", fullName)
        .param("c", codeOfConductVersion)
        .update();
  }

  Optional<Profile> find(UUID accountId) {
    return db.sql(
            """
            SELECT account_id, verification_status, full_name, profession_code, licence_number,
                   years_experience, licensing_body, bio_en, bio_fr, bio_language,
                   practice_saved_at, languages_saved_at, services_saved_at, submitted_at,
                   interview_at, review_note
              FROM specialist_profile WHERE account_id = :id
            """)
        .param("id", accountId)
        .query(ApplicationStore::profile)
        .optional();
  }

  List<String> focusAreas(UUID accountId) {
    return db.sql(
            "SELECT topic_code FROM specialist_focus_area WHERE account_id = :id ORDER BY topic_code")
        .param("id", accountId)
        .query(String.class)
        .list();
  }

  List<Language> languages(UUID accountId) {
    return db.sql(
            """
            SELECT l.language_code, l.proficiency, l.verified_at
              FROM specialist_language l JOIN session_language s ON s.code = l.language_code
             WHERE l.account_id = :id ORDER BY s.position
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new Language(
                    rs.getString("language_code"),
                    rs.getString("proficiency"),
                    instant(rs, "verified_at")))
        .list();
  }

  List<Offering> offerings(UUID accountId) {
    return db.sql(
            """
            SELECT modality, enabled, duration_min, price FROM service_offering
             WHERE specialist_id = :id ORDER BY modality
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new Offering(
                    rs.getString("modality"),
                    rs.getBoolean("enabled"),
                    rs.getInt("duration_min"),
                    rs.getLong("price")))
        .list();
  }

  Optional<Payout> payout(UUID accountId) {
    return db.sql(
            "SELECT method, phone, holder_name FROM specialist_payout_details WHERE account_id = :id")
        .param("id", accountId)
        .query(
            (rs, n) ->
                new Payout(
                    rs.getString("method"), rs.getString("phone"), rs.getString("holder_name")))
        .optional();
  }

  /** When the payout details were last saved: a failed payout stays held until they change. */
  Optional<java.time.Instant> payoutSavedAt(UUID accountId) {
    return db.sql("SELECT saved_at FROM specialist_payout_details WHERE account_id = :id")
        .param("id", accountId)
        .query((rs, n) -> rs.getTimestamp("saved_at").toInstant())
        .optional();
  }

  void savePractice(
      UUID accountId,
      String profession,
      String licenceNumber,
      int yearsExperience,
      String licensingBody,
      String bio,
      String bioLanguage,
      List<String> focusAreas,
      Instant now) {
    // The bio is kept in the language it was written in; the other language waits for its draft.
    db.sql(
            """
            UPDATE specialist_profile
               SET profession_code = :p, licence_number = :l, years_experience = :y,
                   licensing_body = :b, bio_language = :bl,
                   bio_en = CASE WHEN :bl = 'en' THEN :bio ELSE NULL END,
                   bio_fr = CASE WHEN :bl = 'fr' THEN :bio ELSE NULL END,
                   practice_saved_at = :now, updated_at = :now
             WHERE account_id = :id
            """)
        .param("id", accountId)
        .param("p", profession)
        .param("l", licenceNumber)
        .param("y", yearsExperience)
        .param("b", licensingBody)
        .param("bio", bio)
        .param("bl", bioLanguage)
        .param("now", Timestamp.from(now))
        .update();
    db.sql("DELETE FROM specialist_focus_area WHERE account_id = :id")
        .param("id", accountId)
        .update();
    for (String topic : focusAreas) {
      db.sql("INSERT INTO specialist_focus_area (account_id, topic_code) VALUES (:id, :t)")
          .param("id", accountId)
          .param("t", topic)
          .update();
    }
    syncOfferingSpecialty(accountId);
  }

  /** Sessions carry the specialty of the specialist's profession (D31). */
  private void syncOfferingSpecialty(UUID accountId) {
    db.sql(
            """
            UPDATE service_offering o
               SET specialty_code = pr.specialty_code
              FROM specialist_profile p JOIN profession pr ON pr.code = p.profession_code
             WHERE p.account_id = o.specialist_id AND o.specialist_id = :id
            """)
        .param("id", accountId)
        .update();
  }

  /** The bio Solace drafted in the other language (D30), if any. */
  record DraftedBio(String language, String text, Instant confirmedAt) {}

  Optional<DraftedBio> draftedBio(UUID accountId) {
    return db.sql(
            """
            SELECT bio_drafted_language,
                   CASE bio_drafted_language WHEN 'fr' THEN bio_fr ELSE bio_en END AS text,
                   bio_draft_confirmed_at
              FROM specialist_profile
             WHERE account_id = :id AND bio_drafted_language IS NOT NULL
            """)
        .param("id", accountId)
        .query(
            (rs, n) ->
                new DraftedBio(
                    rs.getString("bio_drafted_language"),
                    rs.getString("text"),
                    instant(rs, "bio_draft_confirmed_at")))
        .optional();
  }

  /** The specialist confirms the draft, with their corrections if any. */
  void confirmBio(UUID accountId, String language, String text, Instant now) {
    String column = "fr".equals(language) ? "bio_fr" : "bio_en";
    db.sql(
            "UPDATE specialist_profile SET "
                + column
                + " = COALESCE(:t, "
                + column
                + "), bio_draft_confirmed_at = :now, updated_at = :now WHERE account_id = :id")
        .param("id", accountId)
        .param("t", text)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Replaces the declared languages; a changed level needs confirming again. */
  void saveLanguages(UUID accountId, List<Language> languages, Instant now) {
    db.sql("DELETE FROM specialist_language WHERE account_id = :id")
        .param("id", accountId)
        .update();
    for (Language language : languages) {
      db.sql(
              """
              INSERT INTO specialist_language (account_id, language_code, proficiency)
              VALUES (:id, :c, :p)
              """)
          .param("id", accountId)
          .param("c", language.code())
          .param("p", language.proficiency())
          .update();
    }
    touch(accountId, "languages_saved_at", now);
  }

  void saveOfferings(UUID accountId, List<Offering> offerings, Instant now) {
    db.sql("DELETE FROM service_offering WHERE specialist_id = :id")
        .param("id", accountId)
        .update();
    for (Offering offering : offerings) {
      db.sql(
              """
              INSERT INTO service_offering (id, specialist_id, modality, enabled, duration_min, price)
              VALUES (:oid, :id, :m, :e, :d, :p)
              """)
          .param("oid", UUID.randomUUID())
          .param("id", accountId)
          .param("m", offering.modality())
          .param("e", offering.enabled())
          .param("d", offering.durationMinutes())
          .param("p", offering.price())
          .update();
    }
    syncOfferingSpecialty(accountId);
    touch(accountId, "services_saved_at", now);
  }

  void savePayout(UUID accountId, Payout payout, Instant now) {
    db.sql(
            """
            INSERT INTO specialist_payout_details (account_id, method, phone, holder_name, saved_at)
            VALUES (:id, :m, :p, :h, :now)
            ON CONFLICT (account_id) DO UPDATE
               SET method = EXCLUDED.method, phone = EXCLUDED.phone,
                   holder_name = EXCLUDED.holder_name, saved_at = EXCLUDED.saved_at
            """)
        .param("id", accountId)
        .param("m", payout.method())
        .param("p", payout.phone())
        .param("h", payout.holderName())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** DRAFT to SUBMITTED; true only for the call that made the change. */
  boolean submit(UUID accountId, Instant now) {
    return db.sql(
                """
                UPDATE specialist_profile
                   SET verification_status = 'SUBMITTED', submitted_at = :now, review_note = NULL,
                       updated_at = :now
                 WHERE account_id = :id AND verification_status = 'DRAFT'
                """)
            .param("id", accountId)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  private void touch(UUID accountId, String column, Instant now) {
    db.sql(
            "UPDATE specialist_profile SET "
                + column
                + " = :now, updated_at = :now WHERE account_id = :id")
        .param("id", accountId)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Profile profile(ResultSet rs, int n) throws SQLException {
    return new Profile(
        rs.getObject("account_id", UUID.class),
        ApplicationStatus.valueOf(rs.getString("verification_status")),
        rs.getString("full_name"),
        rs.getString("profession_code"),
        rs.getString("licence_number"),
        (Integer) rs.getObject("years_experience"),
        rs.getString("licensing_body"),
        rs.getString("bio_en"),
        rs.getString("bio_fr"),
        rs.getString("bio_language"),
        instant(rs, "practice_saved_at"),
        instant(rs, "languages_saved_at"),
        instant(rs, "services_saved_at"),
        instant(rs, "submitted_at"),
        instant(rs, "interview_at"),
        rs.getString("review_note"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
