package care.solace.tracking.wording;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.modules.StaffGate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The word list as data (table {@code wording_rule}). Clinical reviewers add and remove entries;
 * every rule save and every readiness check reads the current list, so a change applies at once on
 * every instance.
 */
@Service
public class WordingRules {

  public record Rule(
      UUID id,
      String language,
      String pattern,
      WordList.Kind kind,
      String createdByName,
      Instant createdAt) {}

  private final JdbcClient db;
  private final Clock clock;

  WordingRules(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public List<Rule> all() {
    return db.sql("SELECT * FROM wording_rule ORDER BY language, kind DESC, pattern")
        .query(
            (rs, n) ->
                new Rule(
                    rs.getObject("id", UUID.class),
                    rs.getString("language"),
                    rs.getString("pattern"),
                    WordList.Kind.valueOf(rs.getString("kind")),
                    rs.getString("created_by_name"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /** The list as it is now. */
  public WordList current() {
    return new WordList(
        all().stream().map(r -> new WordList.Entry(r.language(), r.pattern(), r.kind())).toList());
  }

  public boolean allowed(String message, String language) {
    return current().allowed(message, language);
  }

  @Transactional
  public Rule add(String language, String pattern, WordList.Kind kind, StaffGate.Staff staff) {
    requireReviewer(staff);
    String p = WordList.normalise(pattern);
    if (!WordList.valid(p) || !(language.equals("en") || language.equals("fr"))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO wording_rule (id, language, pattern, kind, created_by, created_by_name, created_at)
              VALUES (:id, :lang, :p, :kind, :by, :name, :now)
              """)
          .param("id", id)
          .param("lang", language)
          .param("p", p)
          .param("kind", kind.name())
          .param("by", staff.id())
          .param("name", staff.name())
          .param("now", Timestamp.from(clock.instant()))
          .update();
    } catch (DuplicateKeyException e) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    return all().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  @Transactional
  public Rule delete(UUID id, StaffGate.Staff staff) {
    requireReviewer(staff);
    var rule =
        all().stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    db.sql("DELETE FROM wording_rule WHERE id = :id").param("id", id).update();
    return rule;
  }

  private static void requireReviewer(StaffGate.Staff staff) {
    if (!staff.clinicalReviewer()) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
  }
}
