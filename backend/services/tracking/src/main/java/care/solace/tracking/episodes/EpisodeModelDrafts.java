package care.solace.tracking.episodes;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.TrackingError;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.modules.StaffGate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admin-defined episode models (Tracking Service Spec, Episode models; admin screen 33; D61): the
 * element lifecycle for models. Operations or a Super admin drafts one from published elements; a
 * Clinical reviewer who did not draft the version approves it; publishing puts it in use, so
 * modules offer its signals and phones see its episodes, with no migration and no release. A
 * published version is frozen; a new version of a code replaces it when published.
 */
@Service
public class EpisodeModelDrafts {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One model version in any state, with who drafted and approved it. */
  public record Version(
      UUID id,
      String status,
      EpisodeModel model,
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
  private final ElementLibrary elements;
  private final EpisodeModels models;
  private final EpisodeReconciler reconciler;
  private final Clock clock;

  EpisodeModelDrafts(
      JdbcClient db,
      ElementLibrary elements,
      EpisodeModels models,
      EpisodeReconciler reconciler,
      Clock clock) {
    this.db = db;
    this.elements = elements;
    this.models = models;
    this.reconciler = reconciler;
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
    return db.sql("SELECT * FROM episode_model ORDER BY code, version DESC")
        .query(EpisodeModelDrafts::row)
        .list();
  }

  public Optional<Version> version(UUID id) {
    return db.sql("SELECT * FROM episode_model WHERE id = :id")
        .param("id", id)
        .query(EpisodeModelDrafts::row)
        .optional();
  }

  public List<Review> reviews(UUID id) {
    return db.sql(
            "SELECT * FROM episode_model_review WHERE model_id = :id ORDER BY decided_at DESC")
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
    return EpisodeModelRules.problems(v.model(), elements::latest, newestPublished(v));
  }

  /**
   * Module versions that are not retired and read this model: a rule names one of its signals, or
   * the presentation shows its current day.
   */
  public int usedBy(String code) {
    return db.sql(
            """
            SELECT count(DISTINCT v.id) FROM module_version v
             WHERE v.status <> 'RETIRED'
               AND (v.presentation_json -> 'episode' ->> 'model' = :code
                    OR EXISTS (SELECT 1 FROM rule r, jsonb_array_elements(r.conditions_json) c
                                WHERE r.module_version_id = v.id
                                  AND c ->> 'signal' LIKE :prefix))
            """)
        .param("code", code)
        .param("prefix", code.replace("_", "\\_") + ".%")
        .query(Integer.class)
        .single();
  }

  // --- Drafting ---------------------------------------------------------------------------------

  /** A new model at v1, or the next version of a code that has one. */
  @Transactional
  public Version create(EpisodeModel d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var versions = versions(d.code());
    if (versions.stream().anyMatch(Version::open)) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    int number = versions.isEmpty() ? 1 : versions.get(0).model().version() + 1;
    UUID id = UUID.randomUUID();
    try {
      db.sql(
              """
              INSERT INTO episode_model (id, code, version, status, start_element, end_element,
                                         activity_element, active_values, gap_days, span,
                                         metrics_json, labels_json, drafted_by, drafted_by_name,
                                         created_at)
              VALUES (:id, :code, :version, 'DRAFT', :start, :end, :activity,
                      CAST(:active AS text[]), :gap, :span, CAST(:metrics AS jsonb),
                      CAST(:labels AS jsonb), :by, :name, :now)
              """)
          .param("id", id)
          .param("code", d.code())
          .param("version", number)
          .param("start", d.startElement())
          .param("end", d.endElement())
          .param("activity", d.activityElement())
          .param("active", array(d.activeValues()))
          .param("gap", d.gapDays())
          .param("span", d.span().name())
          .param("metrics", metricsJson(d))
          .param("labels", json(d.labels()))
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
  public Version save(UUID id, EpisodeModel d, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = draft(id);
    if (!v.model().code().equals(d.code())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    db.sql(
            """
            UPDATE episode_model SET start_element = :start, end_element = :end,
                   activity_element = :activity, active_values = CAST(:active AS text[]),
                   gap_days = :gap, span = :span, metrics_json = CAST(:metrics AS jsonb),
                   labels_json = CAST(:labels AS jsonb)
             WHERE id = :id AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("start", d.startElement())
        .param("end", d.endElement())
        .param("activity", d.activityElement())
        .param("active", array(d.activeValues()))
        .param("gap", d.gapDays())
        .param("span", d.span().name())
        .param("metrics", metricsJson(d))
        .param("labels", json(d.labels()))
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
            INSERT INTO episode_model_review (id, model_id, reviewer_id, reviewer_name, decision,
                                              comments, decided_at)
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
              "UPDATE episode_model SET approved_by = :by, approved_by_name = :name"
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
   * retired, and everyone's episodes for the code are rebuilt from what they logged.
   */
  @Transactional
  public Version publish(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() == null) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    // The elements must still be published, and no metric a rule reads may have gone.
    if (!problems(v).isEmpty()) {
      throw new SolaceException(TrackingError.NOT_READY);
    }
    db.sql(
            "UPDATE episode_model SET status = 'RETIRED'"
                + " WHERE code = :code AND status = 'PUBLISHED'")
        .param("code", v.model().code())
        .update();
    db.sql(
            "UPDATE episode_model SET status = 'PUBLISHED', published_at = :now"
                + " WHERE id = :id AND status = 'IN_REVIEW'")
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    afterCommit(() -> reconciler.rebuildAll(v.model()));
    return version(id).orElseThrow();
  }

  /** Stops the model being read; refused while a module version that is not retired reads it. */
  @Transactional
  public Version retire(UUID id, StaffGate.Staff staff) {
    requireDrafter(staff);
    var v = found(id);
    if (!v.status().equals("PUBLISHED") || usedBy(v.model().code()) > 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
    move(id, "PUBLISHED", "RETIRED");
    afterCommit(() -> {}); // only the reload
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
  private EpisodeModel newestPublished(Version v) {
    return versions(v.model().code()).stream()
        .filter(x -> x.status().equals("PUBLISHED") && !x.id().equals(v.id()))
        .map(Version::model)
        .findFirst()
        .orElse(null);
  }

  /** Versions of a code, newest first. */
  private List<Version> versions(String code) {
    return db.sql("SELECT * FROM episode_model WHERE code = :code ORDER BY version DESC")
        .param("code", code)
        .query(EpisodeModelDrafts::row)
        .list();
  }

  private void move(UUID id, String from, String to) {
    int n =
        db.sql("UPDATE episode_model SET status = :to WHERE id = :id AND status = :from")
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .update();
    if (n == 0) {
      throw new SolaceException(TrackingError.WRONG_STATE);
    }
  }

  /** This instance reads the models once the change is committed, then runs the follow-up. */
  private void afterCommit(Runnable then) {
    Runnable all =
        () -> {
          models.reload();
          then.run();
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              all.run();
            }
          });
    } else {
      all.run();
    }
  }

  private static String array(java.util.Collection<String> values) {
    return "{" + String.join(",", values) + "}";
  }

  private static String metricsJson(EpisodeModel d) {
    ArrayNode out = JSON.createArrayNode();
    for (var m : d.metrics()) {
      var node = out.addObject();
      node.put("code", m.code());
      node.put("kind", m.kind().name().toLowerCase(Locale.ROOT));
      if (m.value() != null) {
        node.put("value", m.value());
      }
      node.set("labels", m.labels());
    }
    return out.toString();
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
      var model =
          EpisodeModels.parse(
              rs.getString("code"),
              rs.getInt("version"),
              rs.getString("start_element"),
              rs.getString("end_element"),
              rs.getString("activity_element"),
              Arrays.asList((String[]) rs.getArray("active_values").getArray()),
              rs.getInt("gap_days"),
              rs.getString("span"),
              JSON.readTree(rs.getString("metrics_json")),
              JSON.readTree(rs.getString("labels_json")));
      return new Version(
          rs.getObject("id", UUID.class),
          rs.getString("status"),
          model,
          rs.getString("drafted_by"),
          rs.getString("drafted_by_name"),
          rs.getString("approved_by"),
          rs.getString("approved_by_name"),
          published == null ? null : published.toInstant());
    } catch (JsonProcessingException e) {
      throw new SQLException("Bad episode model JSON", e);
    }
  }
}
