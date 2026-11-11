package care.solace.tracking.modules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code module}, {@code module_version}, {@code module_binding}, {@code rule}, reviews (D51). */
@Repository
public class ModuleStore {

  static final ObjectMapper JSON = new ObjectMapper();

  public record Module(UUID id, String code, String accentColour, Instant createdAt) {}

  public record Details(
      String nameEn,
      String nameFr,
      String descriptionEn,
      String descriptionFr,
      UUID imageAssetId,
      String imageAltEn,
      String imageAltFr,
      Integer minAge,
      String audienceEn,
      String audienceFr,
      JsonNode enrolmentQuestion,
      LocalTime reminderDefault,
      JsonNode presentation) {}

  public record Version(
      UUID id,
      UUID moduleId,
      int major,
      int minor,
      String status,
      Details details,
      String draftedBy,
      String draftedByName,
      Instant submittedAt,
      String approvedBy,
      String approvedByName,
      Instant publishedAt,
      Instant retiredAt,
      Instant createdAt) {

    public String number() {
      return major + "." + minor;
    }
  }

  /**
   * @param groupCode elements with the same group show together on the logging screen
   * @param groupLabel {"en": "...", "fr": "..."}, with the group
   */
  public record Binding(
      UUID elementId, boolean required, int position, String groupCode, JsonNode groupLabel) {

    public Binding(UUID elementId, boolean required, int position) {
      this(elementId, required, position, null, null);
    }
  }

  public record Rule(
      UUID id,
      UUID moduleVersionId,
      String code,
      JsonNode conditions,
      JsonNode unless,
      String outcome,
      List<String> specialtyCodes,
      String messageEn,
      String messageFr,
      int cooldownDays,
      Integer repeatWithinDays,
      int position) {}

  public record Review(String reviewerName, String decision, String comments, Instant decidedAt) {}

  private final JdbcClient db;

  ModuleStore(JdbcClient db) {
    this.db = db;
  }

  // --- Modules and versions
  // -----------------------------------------------------------------------

  public boolean createModule(UUID id, String code, String colour, String by, Instant now) {
    return db.sql(
                """
                INSERT INTO module (id, code, accent_colour, created_by, created_at)
                VALUES (:id, :c, :col, :by, :now) ON CONFLICT (code) DO NOTHING
                """)
            .param("id", id)
            .param("c", code)
            .param("col", colour)
            .param("by", by)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  public List<Module> modules() {
    return db.sql("SELECT * FROM module ORDER BY code").query(ModuleStore::module).list();
  }

  public Optional<Module> module(UUID id) {
    return db.sql("SELECT * FROM module WHERE id = :id")
        .param("id", id)
        .query(ModuleStore::module)
        .optional();
  }

  public Optional<Module> moduleByCode(String code) {
    return db.sql("SELECT * FROM module WHERE code = :c")
        .param("c", code)
        .query(ModuleStore::module)
        .optional();
  }

  public void setColour(UUID moduleId, String colour) {
    db.sql("UPDATE module SET accent_colour = :c WHERE id = :id")
        .param("id", moduleId)
        .param("c", colour)
        .update();
  }

  public void insertVersion(
      UUID id,
      UUID moduleId,
      int major,
      int minor,
      Details d,
      String by,
      String byName,
      Instant now) {
    db.sql(
            """
            INSERT INTO module_version (id, module_id, major, minor, status, name_en, name_fr,
                   description_en, description_fr, image_asset_id, image_alt_en, image_alt_fr,
                   min_age, audience_en, audience_fr, enrolment_question_json, reminder_default,
                   presentation_json, drafted_by, drafted_by_name, created_at)
            VALUES (:id, :m, :maj, :min, 'DRAFT', :ne, :nf, :de, :df, :img, :ae, :af, :age, :aue, :auf,
                    CAST(:q AS jsonb), :rem, CAST(:pres AS jsonb), :by, :name, :now)
            """)
        .param("id", id)
        .param("m", moduleId)
        .param("maj", major)
        .param("min", minor)
        .param("ne", d.nameEn())
        .param("nf", d.nameFr())
        .param("de", d.descriptionEn())
        .param("df", d.descriptionFr())
        .param("img", d.imageAssetId())
        .param("ae", d.imageAltEn())
        .param("af", d.imageAltFr())
        .param("age", d.minAge())
        .param("aue", d.audienceEn())
        .param("auf", d.audienceFr())
        .param("q", json(d.enrolmentQuestion()))
        .param("rem", d.reminderDefault() == null ? null : Time.valueOf(d.reminderDefault()))
        .param("pres", json(d.presentation()))
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  public void saveDetails(UUID versionId, Details d) {
    db.sql(
            """
            UPDATE module_version SET name_en = :ne, name_fr = :nf, description_en = :de,
                   description_fr = :df, image_asset_id = :img, image_alt_en = :ae, image_alt_fr = :af,
                   min_age = :age, audience_en = :aue, audience_fr = :auf,
                   enrolment_question_json = CAST(:q AS jsonb), reminder_default = :rem
             WHERE id = :id AND status = 'DRAFT'
            """)
        .param("id", versionId)
        .param("ne", d.nameEn())
        .param("nf", d.nameFr())
        .param("de", d.descriptionEn())
        .param("df", d.descriptionFr())
        .param("img", d.imageAssetId())
        .param("ae", d.imageAltEn())
        .param("af", d.imageAltFr())
        .param("age", d.minAge())
        .param("aue", d.audienceEn())
        .param("auf", d.audienceFr())
        .param("q", json(d.enrolmentQuestion()))
        .param("rem", d.reminderDefault() == null ? null : Time.valueOf(d.reminderDefault()))
        .update();
  }

  /** The presentation is saved on its own, after the bindings it points at. */
  public void savePresentation(UUID versionId, JsonNode presentation) {
    db.sql(
            "UPDATE module_version SET presentation_json = CAST(:pres AS jsonb)"
                + " WHERE id = :id AND status = 'DRAFT'")
        .param("id", versionId)
        .param("pres", json(presentation))
        .update();
  }

  public Optional<Version> version(UUID id) {
    return db.sql("SELECT * FROM module_version WHERE id = :id")
        .param("id", id)
        .query(ModuleStore::version)
        .optional();
  }

  public List<Version> versions(UUID moduleId) {
    return db.sql(
            "SELECT * FROM module_version WHERE module_id = :m ORDER BY major DESC, minor DESC")
        .param("m", moduleId)
        .query(ModuleStore::version)
        .list();
  }

  public Optional<Version> published(UUID moduleId) {
    return db.sql("SELECT * FROM module_version WHERE module_id = :m AND status = 'PUBLISHED'")
        .param("m", moduleId)
        .query(ModuleStore::version)
        .optional();
  }

  public List<Version> allPublished() {
    return db.sql("SELECT * FROM module_version WHERE status = 'PUBLISHED'")
        .query(ModuleStore::version)
        .list();
  }

  /** Moves a version from one status to another; false when it was not in {@code from}. */
  public boolean move(
      UUID id, String from, String to, String column, String by, String byName, Instant now) {
    String set =
        switch (to) {
          case "IN_REVIEW" -> "submitted_by = :by, submitted_at = :now";
          case "PUBLISHED" -> "published_at = :now";
          case "RETIRED" -> "retired_at = :now";
          case "DRAFT" ->
              "submitted_by = NULL, submitted_at = NULL, approved_by = NULL, approved_by_name = NULL, approved_at = NULL";
          default -> throw new IllegalArgumentException(to);
        };
    return db.sql(
                "UPDATE module_version SET status = :to, "
                    + set
                    + " WHERE id = :id AND status = :from")
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .param("by", by)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  public void approve(UUID id, String by, String byName, Instant now) {
    db.sql(
            "UPDATE module_version SET approved_by = :by, approved_by_name = :name, approved_at = :now WHERE id = :id")
        .param("id", id)
        .param("by", by)
        .param("name", byName)
        .param("now", Timestamp.from(now))
        .update();
  }

  public void renumber(UUID id, int major, int minor) {
    db.sql(
            "UPDATE module_version SET major = :maj, minor = :min WHERE id = :id AND status = 'DRAFT'")
        .param("id", id)
        .param("maj", major)
        .param("min", minor)
        .update();
  }

  /** Enrolled people log against the newly published version from now on. */
  public void moveEnrolments(UUID moduleId, UUID versionId) {
    db.sql("UPDATE enrolment SET module_version_id = :v WHERE module_id = :m AND left_at IS NULL")
        .param("m", moduleId)
        .param("v", versionId)
        .update();
  }

  public int enrolled(UUID moduleId) {
    return db.sql("SELECT count(*) FROM enrolment WHERE module_id = :m AND left_at IS NULL")
        .param("m", moduleId)
        .query(Integer.class)
        .single();
  }

  // --- Bindings and rules -----------------------------------------------------------------------

  public List<Binding> bindings(UUID versionId) {
    return db.sql("SELECT * FROM module_binding WHERE module_version_id = :v ORDER BY position")
        .param("v", versionId)
        .query(
            (rs, n) ->
                new Binding(
                    rs.getObject("element_id", UUID.class),
                    rs.getBoolean("required"),
                    rs.getInt("position"),
                    rs.getString("group_code"),
                    tree(rs.getString("group_label_json"))))
        .list();
  }

  public void replaceBindings(UUID versionId, List<Binding> bindings) {
    db.sql("DELETE FROM module_binding WHERE module_version_id = :v")
        .param("v", versionId)
        .update();
    for (Binding b : bindings) {
      db.sql(
              """
              INSERT INTO module_binding (module_version_id, element_id, required, position,
                     group_code, group_label_json)
              VALUES (:v, :e, :r, :p, :g, CAST(:gl AS jsonb))
              """)
          .param("v", versionId)
          .param("e", b.elementId())
          .param("r", b.required())
          .param("p", b.position())
          .param("g", b.groupCode())
          .param("gl", json(b.groupLabel()))
          .update();
    }
  }

  /** The context questions asked when enrolling in this version, in order (D61). */
  public List<String> questions(UUID versionId) {
    return db.sql(
            "SELECT question_code FROM module_question WHERE module_version_id = :v ORDER BY position")
        .param("v", versionId)
        .query(String.class)
        .list();
  }

  public void replaceQuestions(UUID versionId, List<String> codes) {
    db.sql("DELETE FROM module_question WHERE module_version_id = :v")
        .param("v", versionId)
        .update();
    for (int i = 0; i < codes.size(); i++) {
      db.sql(
              "INSERT INTO module_question (module_version_id, question_code, position) VALUES (:v, :q, :p)")
          .param("v", versionId)
          .param("q", codes.get(i))
          .param("p", i)
          .update();
    }
  }

  public List<Rule> rules(UUID versionId) {
    return db.sql("SELECT * FROM rule WHERE module_version_id = :v ORDER BY position, code")
        .param("v", versionId)
        .query(ModuleStore::rule)
        .list();
  }

  public Optional<Rule> rule(UUID id) {
    return db.sql("SELECT * FROM rule WHERE id = :id")
        .param("id", id)
        .query(ModuleStore::rule)
        .optional();
  }

  public void saveRule(Rule r) {
    db.sql(
            """
            INSERT INTO rule (id, module_version_id, code, conditions_json, unless_json, outcome,
                              specialty_codes, message_en, message_fr, cooldown_days,
                              repeat_within_days, position)
            VALUES (:id, :v, :code, CAST(:c AS jsonb), CAST(:u AS jsonb), :o, :s, :me, :mf, :cd, :rw, :p)
            ON CONFLICT (id) DO UPDATE SET code = EXCLUDED.code, conditions_json = EXCLUDED.conditions_json,
                   unless_json = EXCLUDED.unless_json, outcome = EXCLUDED.outcome,
                   specialty_codes = EXCLUDED.specialty_codes, message_en = EXCLUDED.message_en,
                   message_fr = EXCLUDED.message_fr, cooldown_days = EXCLUDED.cooldown_days,
                   repeat_within_days = EXCLUDED.repeat_within_days, position = EXCLUDED.position
            """)
        .param("id", r.id())
        .param("v", r.moduleVersionId())
        .param("code", r.code())
        .param("c", json(r.conditions()))
        .param("u", json(r.unless()))
        .param("o", r.outcome())
        .param("s", r.specialtyCodes().toArray(String[]::new))
        .param("me", r.messageEn())
        .param("mf", r.messageFr())
        .param("cd", r.cooldownDays())
        .param("rw", r.repeatWithinDays())
        .param("p", r.position())
        .update();
  }

  public void deleteRule(UUID id) {
    db.sql("DELETE FROM rule WHERE id = :id").param("id", id).update();
  }

  // --- Reviews ----------------------------------------------------------------------------------

  public void review(
      UUID versionId, String by, String byName, String decision, String comments, Instant now) {
    db.sql(
            """
            INSERT INTO rule_review (id, module_version_id, reviewer_id, reviewer_name, decision, comments, decided_at)
            VALUES (:id, :v, :by, :name, :d, :c, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("v", versionId)
        .param("by", by)
        .param("name", byName)
        .param("d", decision)
        .param("c", comments)
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<Review> reviews(UUID versionId) {
    return db.sql("SELECT * FROM rule_review WHERE module_version_id = :v ORDER BY decided_at DESC")
        .param("v", versionId)
        .query(
            (rs, n) ->
                new Review(
                    rs.getString("reviewer_name"),
                    rs.getString("decision"),
                    rs.getString("comments"),
                    rs.getTimestamp("decided_at").toInstant()))
        .list();
  }

  // --- Mapping ----------------------------------------------------------------------------------

  static String json(JsonNode node) {
    try {
      return node == null || node.isNull() ? null : JSON.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode tree(String json) throws SQLException {
    try {
      return json == null ? null : JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new SQLException(e);
    }
  }

  private static Module module(ResultSet rs, int n) throws SQLException {
    return new Module(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("accent_colour"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static Version version(ResultSet rs, int n) throws SQLException {
    Time reminder = rs.getTime("reminder_default");
    return new Version(
        rs.getObject("id", UUID.class),
        rs.getObject("module_id", UUID.class),
        rs.getInt("major"),
        rs.getInt("minor"),
        rs.getString("status"),
        new Details(
            rs.getString("name_en"),
            rs.getString("name_fr"),
            rs.getString("description_en"),
            rs.getString("description_fr"),
            rs.getObject("image_asset_id", UUID.class),
            rs.getString("image_alt_en"),
            rs.getString("image_alt_fr"),
            (Integer) rs.getObject("min_age"),
            rs.getString("audience_en"),
            rs.getString("audience_fr"),
            tree(rs.getString("enrolment_question_json")),
            reminder == null ? null : reminder.toLocalTime(),
            tree(rs.getString("presentation_json"))),
        rs.getString("drafted_by"),
        rs.getString("drafted_by_name"),
        instant(rs.getTimestamp("submitted_at")),
        rs.getString("approved_by"),
        rs.getString("approved_by_name"),
        instant(rs.getTimestamp("published_at")),
        instant(rs.getTimestamp("retired_at")),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Rule rule(ResultSet rs, int n) throws SQLException {
    return new Rule(
        rs.getObject("id", UUID.class),
        rs.getObject("module_version_id", UUID.class),
        rs.getString("code"),
        tree(rs.getString("conditions_json")),
        tree(rs.getString("unless_json")),
        rs.getString("outcome"),
        Arrays.asList((String[]) rs.getArray("specialty_codes").getArray()),
        rs.getString("message_en"),
        rs.getString("message_fr"),
        rs.getInt("cooldown_days"),
        (Integer) rs.getObject("repeat_within_days"),
        rs.getInt("position"));
  }
}
