package care.solace.core.catalog;

import care.solace.events.Events;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class JdbcCatalogue implements Catalogue {

  private final JdbcClient db;
  private final ApplicationEventPublisher events;

  JdbcCatalogue(JdbcClient db, ApplicationEventPublisher events) {
    this.db = db;
    this.events = events;
  }

  @Override
  public List<Entry> sessionLanguages(Locale viewer) {
    String column = french(viewer) ? "name_fr" : "name_en";
    return db.sql(
            "SELECT code, "
                + column
                + " AS label FROM session_language"
                + " WHERE enabled ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public List<Entry> helpTopics(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql(
            "SELECT code, " + column + " AS label FROM help_topic WHERE enabled ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public List<Entry> professions(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql(
            "SELECT code, " + column + " AS label FROM profession WHERE enabled ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public List<Entry> specialties(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql(
            "SELECT code, " + column + " AS label FROM specialty WHERE enabled ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public List<Specialty> specialtyTree(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql(
            "SELECT code, "
                + column
                + " AS label, parent_code FROM specialty WHERE enabled ORDER BY position")
        .query(
            (rs, n) ->
                new Specialty(
                    rs.getString("code"), rs.getString("label"), rs.getString("parent_code")))
        .list();
  }

  @Override
  public List<Entry> allProfessions(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql("SELECT code, " + column + " AS label FROM profession ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public List<Entry> allSpecialties(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql("SELECT code, " + column + " AS label FROM specialty ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public Optional<String> professionFor(String specialty, Locale viewer) {
    String column = french(viewer) ? "p.label_fr" : "p.label_en";
    // A profession that allows this specialty, else one that allows its main specialty.
    return db.sql(
            """
            SELECT %s FROM profession p JOIN profession_specialty ps ON ps.profession_code = p.code
             WHERE ps.specialty_code = :s
                OR ps.specialty_code = (SELECT parent_code FROM specialty WHERE code = :s)
             ORDER BY (ps.specialty_code = :s) DESC, p.position
             LIMIT 1
            """
                .formatted(column))
        .param("s", specialty)
        .query(String.class)
        .optional();
  }

  @Override
  public boolean areSessionLanguages(Collection<String> codes) {
    return allEnabled("session_language", codes);
  }

  @Override
  public boolean areHelpTopics(Collection<String> codes) {
    return allEnabled("help_topic", codes);
  }

  @Override
  public boolean isProfession(String code) {
    return allEnabled("profession", List.of(code));
  }

  @Override
  public boolean specialtyExists(String code) {
    return db.sql("SELECT count(*) FROM specialty WHERE code = :c")
            .param("c", code)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public boolean isMainSpecialty(String code) {
    return db.sql(
                "SELECT count(*) FROM specialty WHERE code = :c AND enabled AND parent_code IS NULL")
            .param("c", code)
            .query(Integer.class)
            .single()
        == 1;
  }

  @Override
  public Set<String> allowedSpecialties(String profession) {
    return Set.copyOf(
        db.sql(
                """
                SELECT ps.specialty_code FROM profession_specialty ps
                  JOIN specialty s ON s.code = ps.specialty_code
                 WHERE ps.profession_code = :p AND s.enabled
                """)
            .param("p", profession)
            .query(String.class)
            .list());
  }

  @Override
  public void createSpecialty(String code, String labelEn, String labelFr, String parentCode) {
    db.sql(
            """
            INSERT INTO specialty (code, label_en, label_fr, parent_code, position, enabled)
            VALUES (:c, :en, :fr, :p, (SELECT coalesce(max(position), 0) + 1 FROM specialty), TRUE)
            """)
        .param("c", code)
        .param("en", labelEn)
        .param("fr", labelFr)
        .param("p", parentCode)
        .update();
    events.publishEvent(
        Events.platformEnvelope(
            "core.config.changed",
            1,
            new AdminCatalogueController.ConfigChanged("SPECIALTIES", code, 1)));
  }

  @Override
  public boolean linkSpecialty(String profession, String specialty) {
    return db.sql(
                """
                INSERT INTO profession_specialty (profession_code, specialty_code)
                VALUES (:p, :s) ON CONFLICT DO NOTHING
                """)
            .param("p", profession)
            .param("s", specialty)
            .update()
        == 1;
  }

  private boolean allEnabled(String table, Collection<String> codes) {
    if (codes.isEmpty()) {
      return true;
    }
    var distinct = new HashSet<>(codes);
    Integer found =
        db.sql("SELECT count(*) FROM " + table + " WHERE enabled AND code IN (:codes)")
            .param("codes", distinct)
            .query(Integer.class)
            .single();
    return found == distinct.size();
  }

  private static boolean french(Locale viewer) {
    return "fr".equals(viewer.getLanguage());
  }
}
