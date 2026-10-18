package care.solace.core.catalog;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class JdbcCatalogue implements Catalogue {

  private final JdbcClient db;

  JdbcCatalogue(JdbcClient db) {
    this.db = db;
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
  public List<Entry> allProfessions(Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql("SELECT code, " + column + " AS label FROM profession ORDER BY position")
        .query((rs, n) -> new Entry(rs.getString("code"), rs.getString("label")))
        .list();
  }

  @Override
  public java.util.Optional<String> professionFor(String specialty, Locale viewer) {
    String column = french(viewer) ? "label_fr" : "label_en";
    return db.sql(
            "SELECT "
                + column
                + " FROM profession WHERE specialty_code = :s ORDER BY position LIMIT 1")
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
