package care.solace.core.specialists;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Listed specialists as seekers and guests find them (System Design, Catalog and search; D31).
 * Listed means approved, bookable in the session language (verified at Professional or above), with
 * an enabled session, and a bio in EN and FR whose Solace draft, if any, the specialist confirmed
 * (D30). PostgreSQL full-text search over names, professions, focus areas and bios in both
 * languages, accents ignored.
 */
@Repository
class DirectoryStore {

  /** The most candidates one search looks at before ordering; ample for the year-one scale. */
  static final int CANDIDATES = 300;

  private static final String LISTED =
      """
      p.verification_status = 'APPROVED'
      AND p.bio_en IS NOT NULL AND p.bio_fr IS NOT NULL
      AND (p.bio_drafted_language IS NULL OR p.bio_draft_confirmed_at IS NOT NULL)
      AND EXISTS (SELECT 1 FROM service_offering o
                   WHERE o.specialist_id = p.account_id AND o.enabled)
      """;

  /** Everything a seeker might type that should find this specialist, in both languages. */
  private static final String DOCUMENT =
      """
      to_tsvector('simple', unaccent(concat_ws(' ',
          p.full_name, pr.label_en, pr.label_fr, sp.label_en, sp.label_fr, p.bio_en, p.bio_fr,
          (SELECT string_agg(t.label_en || ' ' || t.label_fr, ' ')
             FROM specialist_focus_area f JOIN help_topic t ON t.code = f.topic_code
            WHERE f.account_id = p.account_id))))
      """;

  private final JdbcClient db;

  DirectoryStore(JdbcClient db) {
    this.db = db;
  }

  record Listed(
      UUID id,
      String fullName,
      String profession,
      String specialty,
      String bioEn,
      String bioFr,
      double rank) {}

  record Offer(String modality, int durationMinutes, long price) {}

  record Language(String code, String level) {}

  /**
   * Listed specialists bookable in {@code language} who match every filter given.
   *
   * @param tsQuery a prefix query built from the seeker's words, or null for no text
   */
  List<Listed> search(
      String language, String modality, String specialty, Long maxPrice, String tsQuery) {
    return db.sql(
            """
            SELECT p.account_id, p.full_name, p.profession_code, pr.specialty_code, p.bio_en,
                   p.bio_fr,
                   CASE WHEN CAST(:q AS TEXT) IS NULL THEN 0
                        ELSE ts_rank(%2$s, to_tsquery('simple', :q)) END AS rank
              FROM specialist_profile p
              JOIN profession pr ON pr.code = p.profession_code
              JOIN specialty sp ON sp.code = pr.specialty_code
             WHERE %1$s
               AND EXISTS (SELECT 1 FROM specialist_language l
                            WHERE l.account_id = p.account_id AND l.language_code = :language
                              AND l.verified_level IN ('NATIVE', 'FLUENT', 'PROFESSIONAL'))
               AND EXISTS (SELECT 1 FROM service_offering o
                            WHERE o.specialist_id = p.account_id AND o.enabled
                              AND (CAST(:modality AS TEXT) IS NULL OR o.modality = :modality)
                              AND (CAST(:maxPrice AS BIGINT) IS NULL OR o.price <= :maxPrice))
               AND (CAST(:specialty AS TEXT) IS NULL OR pr.specialty_code = :specialty)
               AND (CAST(:q AS TEXT) IS NULL OR %2$s @@ to_tsquery('simple', :q))
             LIMIT %3$d
            """
                .formatted(LISTED, DOCUMENT, CANDIDATES))
        .param("language", language)
        .param("modality", modality)
        .param("maxPrice", maxPrice)
        .param("specialty", specialty)
        .param("q", tsQuery)
        .query(
            (rs, n) ->
                new Listed(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getString("profession_code"),
                    rs.getString("specialty_code"),
                    rs.getString("bio_en"),
                    rs.getString("bio_fr"),
                    rs.getDouble("rank")))
        .list();
  }

  /** One listed specialist, whatever the session language. */
  Optional<Listed> listed(UUID id) {
    return db.sql(
            """
            SELECT p.account_id, p.full_name, p.profession_code, pr.specialty_code, p.bio_en,
                   p.bio_fr, 0 AS rank
              FROM specialist_profile p
              JOIN profession pr ON pr.code = p.profession_code
             WHERE p.account_id = :id AND %s
            """
                .formatted(LISTED))
        .param("id", id)
        .query(
            (rs, n) ->
                new Listed(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getString("profession_code"),
                    rs.getString("specialty_code"),
                    rs.getString("bio_en"),
                    rs.getString("bio_fr"),
                    0))
        .optional();
  }

  List<Offer> offers(UUID id) {
    return db.sql(
            """
            SELECT modality, duration_min, price FROM service_offering
             WHERE specialist_id = :id AND enabled ORDER BY modality
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new Offer(rs.getString("modality"), rs.getInt("duration_min"), rs.getLong("price")))
        .list();
  }

  /** Languages verified at a bookable level, in catalogue order. */
  List<Language> bookableLanguages(UUID id) {
    return db.sql(
            """
            SELECT l.language_code, l.verified_level
              FROM specialist_language l JOIN session_language s ON s.code = l.language_code
             WHERE l.account_id = :id AND l.verified_level IN ('NATIVE', 'FLUENT', 'PROFESSIONAL')
             ORDER BY s.position
            """)
        .param("id", id)
        .query(
            (rs, n) -> new Language(rs.getString("language_code"), rs.getString("verified_level")))
        .list();
  }

  /** Focus area labels in one language, in the catalogue's order. */
  List<String> focusAreas(UUID id, boolean french) {
    return db.sql(
            "SELECT "
                + (french ? "t.label_fr" : "t.label_en")
                + " FROM specialist_focus_area f JOIN help_topic t ON t.code = f.topic_code"
                + " WHERE f.account_id = :id ORDER BY t.position")
        .param("id", id)
        .query(String.class)
        .list();
  }

  /** Focus area codes, for matching on the seeker's topics (the health check). */
  List<String> focusCodes(UUID id) {
    return db.sql("SELECT topic_code FROM specialist_focus_area WHERE account_id = :id")
        .param("id", id)
        .query(String.class)
        .list();
  }
}
