package care.solace.tracking.context;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.TrackingError;
import care.solace.tracking.modules.StaffGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admin-defined context questions (Tracking Service Spec, Context questions; admin screen 34; D61):
 * the element lifecycle for questions. Operations or a Super admin drafts one; a Clinical reviewer
 * who did not draft the version approves it; publishing puts it in use, so modules can ask it on
 * enrolment or read it in a rule's "unless" and phones show it, with no migration and no release. A
 * published version is frozen; a new version of a code replaces it when published.
 */
@Service
public class ContextQuestionDrafts {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The old profile field a launch question replaced, which rules drafted before it still name. */
  private static final Map<String, String> LEGACY_FIELD =
      Map.of("trying_to_conceive", "trying_to_conceive", "hormonal_treatment", "treatments");

  /** One question version in any state, with who drafted and approved it. */
  public record Version(
      UUID id,
      String status,
      ContextQuestion question,
      String draftedBy,
      String draftedByName,
      String approvedBy,
      String approvedByName,
      Instant publishedAt) {

    public boolean open() {
      return status.equals("DRAFT") || status.equals("IN_REVIEW");
    }
  }

  public record Review(String reviewerName, String decision, String comments, Instant decidedAt) {}

  private final JdbcClient db;
  private final ContextQuestions questions;
  private final Clock clock;

  ContextQuestionDrafts(JdbcClient db, ContextQuestions questions, Clock clock) {
    this.db = db;
    this.questions = questions;
    this.clock = clock;
  }

  static void requireDrafter(StaffGate.Staff staff) {
    if (!staff.roles().contains("OPERATIONS") && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
  }

  // --- Reading ----------------------------------------------------------------------------------

  /** Every version, by code, newest first within a code. */
  public List<Version> all() {
    return db.sql("SELECT * FROM context_question ORDER BY code, version DESC")
        .query(ContextQuestionDrafts::row)
        .list();
  }

  public Optional<Version> version(UUID id) {
    return db.sql("SELECT * FROM context_question WHERE id = :id")
        .param("id", id)
        .query(ContextQuestionDrafts::row)
        .optional();
  }

  public List<Review> reviews(UUID id) {
    return db.sql(
            "SELECT * FROM context_question_review WHERE question_id = :id"
                + " ORDER BY decided_at DESC")
        .param("id", id)
        .query(
            (rs, n) ->
                new Review(
                    rs.getString("reviewer_name"),
                    rs.getString("decision"),
                    rs.getString("comments"),
                    rs.getTimestamp("decided_at").toInstant()))
        .list();
  }

  public List<String> problems(Version v) {
    return ContextQuestionRules.problems(v.question(), newestPublished(v));
  }

  /**
   * Module versions that are not retired and use this question: they ask it on enrolment, or a
   * rule's "unless" reads it (by code, or by the old profile field it replaced).
   */
  public int usedBy(String code) {
    String field = LEGACY_FIELD.get(code);
    return db.sql(
            """
            SELECT count(DISTINCT v.id) FROM module_version v
             WHERE v.status <> 'RETIRED'
               AND (EXISTS (SELECT 1 FROM module_question q
                             WHERE q.module_version_id = v.id AND q.question_code = :code)
                    OR EXISTS (SELECT 1 FROM rule r
                                WHERE r.module_version_id = v.id
                                  AND (r.unless_json @> CAST(:byCode AS jsonb)
                                       OR r.unless_json @> CAST(:byField AS jsonb))))
            """)
        .param("code", code)
        .param("byCode", entry("question", code))
        .param("byField", field == null ? entry("question", code) : entry("field", field))
        .query(Integer.class)
        .single();
  }

  // --- Drafting ---------------------------------------------------------------------------------

  /** A new question at v1, or the next version of a code that has one. */
  @Transactional
  public Version create(ContextQuestion d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var versions = versions(d.code());
    if (versions.stream().anyMatch(Version::open)) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    int number = versions.isEmpty() ? 1 : versions.get(0).question().version() + 1;
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO context_question (id, code, version, status, type, scope, labels_json,
                                            options_json, drafted_by, drafted_by_name, created_at)
              VALUES (:id, :code, :version, 'DRAFT', :type, :scope, CAST(:labels AS jsonb),
                      CAST(:options AS jsonb), :by, :name, :now)
              """)
          .param("id", id)
          .param("code", d.code())
          .param("version", number)
          .param("type", d.type().name())
          .param("scope", d.scope().name())
          .param("labels", d.labels().toString())
          .param("options", optionsJson(d))
          .param("by", staff.id())
          .param("name", staff.name())
          .param("now", Timestamp.from(clock.instant()))
          .update();
    } catch (DuplicateKeyException e) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    return version(id).orElseThrow();
  }

  /** Saves a draft; the code and the version number stay. */
  @Transactional
  public Version save(UUID id, ContextQuestion d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(id);
    if (!v.question().code().equals(d.code())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    db.sql(
            """
            UPDATE context_question SET type = :type, scope = :scope,
                   labels_json = CAST(:labels AS jsonb), options_json = CAST(:options AS jsonb)
             WHERE id = :id AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("type", d.type().name())
        .param("scope", d.scope().name())
        .param("labels", d.labels().toString())
        .param("options", optionsJson(d))
        .update();
    return version(id).orElseThrow();
  }

  @Transactional
  public Version submit(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(id);
    if (!problems(v).isEmpty()) {
      throw new SolaceException(TrackingError.NOT_READY);
    }
    move(id, "DRAFT", "IN_REVIEW");
    return version(id).orElseThrow();
  }

  /** A Clinical reviewer approves, or returns the version to draft with comments. */
  @Transactional
  public Version review(UUID id, boolean approve, String comments, StaffGate.Staff staff) {
    if (!staff.clinicalReviewer()) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    var v = found(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() != null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    if (staff.id().equals(v.draftedBy())) {
      throw new SolaceException(TrackingError.SAME_PERSON);
    }
    db.sql(
            """
            INSERT INTO context_question_review (id, question_id, reviewer_id, reviewer_name,
                                                 decision, comments, decided_at)
            VALUES (:rid, :id, :by, :name, :d, :c, :now)
            """)
        .param("rid", UUID.randomUUID())
        .param("id", id)
        .param("by", staff.id())
        .param("name", staff.name())
        .param("d", approve ? "APPROVED" : "RETURNED")
        .param("c", comments)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    if (approve) {
      db.sql(
              "UPDATE context_question SET approved_by = :by, approved_by_name = :name"
                  + " WHERE id = :id AND status = 'IN_REVIEW'")
          .param("id", id)
          .param("by", staff.id())
          .param("name", staff.name())
          .update();
    } else {
      move(id, "IN_REVIEW", "DRAFT");
    }
    return version(id).orElseThrow();
  }

  /**
   * Publishes an approved version. It replaces the code's older published version, which is
   * retired; stored answers stay, since the new version keeps the type and options.
   */
  @Transactional
  public Version publish(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() == null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    if (!problems(v).isEmpty()) {
      throw new SolaceException(TrackingError.NOT_READY);
    }
    db.sql(
            "UPDATE context_question SET status = 'RETIRED'"
                + " WHERE code = :code AND status = 'PUBLISHED'")
        .param("code", v.question().code())
        .update();
    db.sql(
            "UPDATE context_question SET status = 'PUBLISHED', published_at = :now"
                + " WHERE id = :id AND status = 'IN_REVIEW'")
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    reloadAfterCommit();
    return version(id).orElseThrow();
  }

  /** Stops the question being asked; refused while a module version that is not retired uses it. */
  @Transactional
  public Version retire(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("PUBLISHED") || usedBy(v.question().code()) > 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    move(id, "PUBLISHED", "RETIRED");
    reloadAfterCommit();
    return version(id).orElseThrow();
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private Version found(UUID id) {
    return version(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private Version draft(UUID id) {
    var v = found(id);
    if (!v.status().equals("DRAFT")) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    return v;
  }

  /** The newest published version of the same code that is not this one, or null. */
  private ContextQuestion newestPublished(Version v) {
    return versions(v.question().code()).stream()
        .filter(x -> x.status().equals("PUBLISHED") && !x.id().equals(v.id()))
        .map(Version::question)
        .findFirst()
        .orElse(null);
  }

  /** Versions of a code, newest first. */
  private List<Version> versions(String code) {
    return db.sql("SELECT * FROM context_question WHERE code = :code ORDER BY version DESC")
        .param("code", code)
        .query(ContextQuestionDrafts::row)
        .list();
  }

  private void move(UUID id, String from, String to) {
    int n =
        db.sql("UPDATE context_question SET status = :to WHERE id = :id AND status = :from")
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .update();
    if (n == 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
  }

  /** This instance reads the questions again once the change is committed. */
  private void reloadAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              questions.reload();
            }
          });
    } else {
      questions.reload();
    }
  }

  /** A one-entry "unless" list, for a containment match. */
  private static String entry(String key, String value) {
    ArrayNode list = JSON.createArrayNode();
    list.addObject().put(key, value);
    return list.toString();
  }

  private static String optionsJson(ContextQuestion d) {
    ArrayNode out = JSON.createArrayNode();
    for (var o : d.options()) {
      var node = out.addObject();
      node.put("code", o.code());
      node.set("labels", o.labels());
    }
    return out.toString();
  }

  private static Version row(ResultSet rs, int n) throws SQLException {
    Timestamp published = rs.getTimestamp("published_at");
    return new Version(
        rs.getObject("id", UUID.class),
        rs.getString("status"),
        ContextQuestions.row(rs, n),
        rs.getString("drafted_by"),
        rs.getString("drafted_by_name"),
        rs.getString("approved_by"),
        rs.getString("approved_by_name"),
        published == null ? null : published.toInstant());
  }
}
