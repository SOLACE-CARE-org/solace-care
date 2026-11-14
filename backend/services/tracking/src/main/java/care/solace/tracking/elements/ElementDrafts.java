package care.solace.tracking.elements;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.TrackingError;
import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.elements.ElementRules.Draft;
import care.solace.tracking.modules.StaffGate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
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
 * Admin-defined elements (Tracking Service Spec, Elements; D61): the module lifecycle for elements.
 * Operations or a Super admin drafts from a registered input view; a Clinical reviewer who did not
 * draft the version approves it; publishing puts it in the library, so modules can bind it and
 * phones can log it, with no migration and no release. A published version is frozen.
 */
@Service
public class ElementDrafts {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One element version in any state, with who drafted and approved it. */
  public record Version(
      UUID id,
      int major,
      int minor,
      String status,
      Draft draft,
      List<String> signals,
      String minAppVersion,
      String draftedBy,
      String draftedByName,
      String approvedBy,
      String approvedByName,
      Instant publishedAt) {

    public String number() {
      return minor == 0 ? String.valueOf(major) : major + "." + minor;
    }

    public boolean open() {
      return status.equals("DRAFT") || status.equals("IN_REVIEW");
    }

    Element element() {
      return new Element(
          id,
          draft.code(),
          major,
          minor,
          draft.kind(),
          draft.valueType(),
          draft.constraints(),
          draft.inputView(),
          draft.icon(),
          signals,
          draft.labels(),
          minAppVersion,
          draft.viewProps(),
          status);
    }
  }

  public record Review(String reviewerName, String decision, String comments, Instant decidedAt) {}

  private final JdbcClient db;
  private final InputViews views;
  private final ElementLibrary library;
  private final Clock clock;

  ElementDrafts(JdbcClient db, InputViews views, ElementLibrary library, Clock clock) {
    this.db = db;
    this.views = views;
    this.library = library;
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
    return db.sql("SELECT * FROM element ORDER BY code, major DESC, minor DESC")
        .query(ElementDrafts::row)
        .list();
  }

  public Optional<Version> version(UUID id) {
    return db.sql("SELECT * FROM element WHERE id = :id")
        .param("id", id)
        .query(ElementDrafts::row)
        .optional();
  }

  public List<Review> reviews(UUID id) {
    return db.sql("SELECT * FROM element_review WHERE element_id = :id ORDER BY decided_at DESC")
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

  /** Module versions that are not retired and bind this element version. */
  public int boundBy(UUID id) {
    return db.sql(
            """
            SELECT count(*) FROM module_binding b JOIN module_version v ON v.id = b.module_version_id
             WHERE b.element_id = :id AND v.status <> 'RETIRED'
            """)
        .param("id", id)
        .query(Integer.class)
        .single();
  }

  public List<String> problems(Version v) {
    return ElementRules.problems(v.draft(), views.find(v.draft().inputView()).orElse(null));
  }

  // --- Drafting ---------------------------------------------------------------------------------

  /** A new element at v1, or a new version of a code that has one, numbered from its changes. */
  @Transactional
  public Version create(Draft d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var versions = versions(d.code());
    if (versions.stream().anyMatch(Version::open)) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    int[] number =
        versions.isEmpty() ? new int[] {1, 0} : ElementRules.number(d, versions.get(0).element());
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO element (id, code, major, minor, kind, value_type, constraints_json,
                                   input_view, view_props_json, icon, signals, labels_json,
                                   min_app_version, status, drafted_by, drafted_by_name, created_at)
              VALUES (:id, :code, :major, :minor, :kind, :type, CAST(:c AS jsonb), :view,
                      CAST(:props AS jsonb), :icon, CAST(:signals AS text[]), CAST(:labels AS jsonb),
                      :app, 'DRAFT', :by, :name, :now)
              """)
          .param("id", id)
          .param("code", d.code())
          .param("major", number[0])
          .param("minor", number[1])
          .params(content(d))
          .param("by", staff.id())
          .param("name", staff.name())
          .param("now", Timestamp.from(clock.instant()))
          .update();
    } catch (DuplicateKeyException e) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    return version(id).orElseThrow();
  }

  /** Saves a draft; the code stays, and the number follows the changes. */
  @Transactional
  public Version save(UUID id, Draft d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(id);
    if (!v.draft().code().equals(d.code())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var older = versions(d.code()).stream().filter(x -> !x.open()).toList();
    int[] number =
        older.isEmpty() ? new int[] {1, 0} : ElementRules.number(d, older.get(0).element());
    db.sql(
            """
            UPDATE element SET major = :major, minor = :minor, kind = :kind, value_type = :type,
                   constraints_json = CAST(:c AS jsonb), input_view = :view,
                   view_props_json = CAST(:props AS jsonb), icon = :icon,
                   signals = CAST(:signals AS text[]), labels_json = CAST(:labels AS jsonb),
                   min_app_version = :app
             WHERE id = :id AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("major", number[0])
        .param("minor", number[1])
        .params(content(d))
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
    Instant now = clock.instant();
    db.sql(
            """
            INSERT INTO element_review (id, element_id, reviewer_id, reviewer_name, decision, comments, decided_at)
            VALUES (:rid, :id, :by, :name, :d, :c, :now)
            """)
        .param("rid", UUID.randomUUID())
        .param("id", id)
        .param("by", staff.id())
        .param("name", staff.name())
        .param("d", approve ? "APPROVED" : "RETURNED")
        .param("c", comments)
        .param("now", Timestamp.from(now))
        .update();
    if (approve) {
      db.sql(
              "UPDATE element SET approved_by = :by, approved_by_name = :name"
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

  /** Publishes an approved version; older versions stay, since modules bind a version. */
  @Transactional
  public Version publish(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() == null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    db.sql(
            "UPDATE element SET status = 'PUBLISHED', published_at = :now"
                + " WHERE id = :id AND status = 'IN_REVIEW'")
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    refreshAfterCommit();
    return version(id).orElseThrow();
  }

  /** No new module can bind it; what people logged stays readable. */
  @Transactional
  public Version retire(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("PUBLISHED") || boundBy(id) > 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    move(id, "PUBLISHED", "RETIRED");
    refreshAfterCommit();
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

  /** Versions of a code, newest first. */
  private List<Version> versions(String code) {
    return db.sql("SELECT * FROM element WHERE code = :code ORDER BY major DESC, minor DESC")
        .param("code", code)
        .query(ElementDrafts::row)
        .list();
  }

  private void move(UUID id, String from, String to) {
    int n =
        db.sql("UPDATE element SET status = :to WHERE id = :id AND status = :from")
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .update();
    if (n == 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
  }

  /** This instance reads the library once the change is committed; others on their next poll. */
  private void refreshAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              library.reload();
            }
          });
    } else {
      library.reload();
    }
  }

  /** The content columns, with signals and the app version derived. */
  private Map<String, Object> content(Draft d) {
    String app = views.find(d.inputView()).map(InputViews.InputView::minAppVersion).orElse("");
    return Map.of(
        "kind", d.kind().name(),
        "type", d.valueType().name(),
        "c", json(d.constraints()),
        "view", d.inputView() == null ? "" : d.inputView(),
        "props", json(d.viewProps()),
        "icon", d.icon() == null ? "" : d.icon(),
        "signals", "{" + String.join(",", ElementRules.signals(d.valueType())) + "}",
        "labels", json(d.labels()),
        "app", app);
  }

  private static String json(JsonNode node) {
    try {
      return node == null || node.isNull() ? "{}" : JSON.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Version row(ResultSet rs, int n) throws SQLException {
    try {
      Timestamp published = rs.getTimestamp("published_at");
      return new Version(
          rs.getObject("id", UUID.class),
          rs.getInt("major"),
          rs.getInt("minor"),
          rs.getString("status"),
          new Draft(
              rs.getString("code"),
              Kind.valueOf(rs.getString("kind")),
              ValueType.valueOf(rs.getString("value_type")),
              JSON.readTree(rs.getString("constraints_json")),
              rs.getString("input_view"),
              JSON.readTree(rs.getString("view_props_json")),
              rs.getString("icon"),
              JSON.readTree(rs.getString("labels_json"))),
          Arrays.asList((String[]) rs.getArray("signals").getArray()),
          rs.getString("min_app_version"),
          rs.getString("drafted_by"),
          rs.getString("drafted_by_name"),
          rs.getString("approved_by"),
          rs.getString("approved_by_name"),
          published == null ? null : published.toInstant());
    } catch (JsonProcessingException e) {
      throw new SQLException("Bad element JSON", e);
    }
  }
}
