package care.solace.core.healthcheck;

import care.solace.core.admin.StaffAccess;
import care.solace.core.healthcheck.Definition.Kind;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Definition.Rule;
import care.solace.core.healthcheck.Definition.TestSet;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health check versions (System Design, Health check, Versions; D55): Draft, In review, Published,
 * Retired. A Clinical reviewer (or a Super admin) writes; a different Clinical reviewer approves;
 * the approver or a Super admin publishes. Wording, points, reasons or priority make a minor
 * version; adding or removing a question or option makes a major one.
 */
@Service
class Versions {

  record Version(
      UUID id,
      int major,
      int minor,
      String status,
      List<Question> questions,
      List<Rule> rules,
      List<String> specialtyOrder,
      List<TestSet> testSets,
      UUID baseVersionId,
      String draftedBy,
      String draftedByName,
      String approvedBy,
      String approvedByName,
      Instant publishedAt,
      Instant createdAt) {

    String number() {
      return major + "." + minor;
    }
  }

  record Review(String reviewer, String decision, String comments, Instant decidedAt) {}

  private static final TypeReference<List<Question>> QUESTIONS = new TypeReference<>() {};
  private static final TypeReference<List<Rule>> RULES = new TypeReference<>() {};
  private static final TypeReference<List<TestSet>> TEST_SETS = new TypeReference<>() {};

  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;

  Versions(JdbcClient db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  // --- Reading ----------------------------------------------------------------------------------

  List<Version> all() {
    return db.sql("SELECT * FROM hc_version ORDER BY major DESC, minor DESC")
        .query(this::row)
        .list();
  }

  Optional<Version> find(UUID id) {
    return db.sql("SELECT * FROM hc_version WHERE id = :id")
        .param("id", id)
        .query(this::row)
        .optional();
  }

  Version require(UUID id) {
    return find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  Optional<Version> published() {
    return db.sql("SELECT * FROM hc_version WHERE status = 'PUBLISHED'")
        .query(this::row)
        .optional();
  }

  List<Review> reviews(UUID versionId) {
    return db.sql(
            "SELECT reviewer_name, decision, comments, decided_at FROM hc_review"
                + " WHERE version_id = :v ORDER BY decided_at")
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

  // --- Writing ----------------------------------------------------------------------------------

  /** The v1 draft, when there is no version at all. */
  @Transactional
  Version seed(String by, String byName) {
    return insert(
        1,
        0,
        Canon.v1Questions(),
        Canon.v1Rules(),
        Canon.v1SpecialtyOrder(),
        Canon.v1TestSets(),
        null,
        by,
        byName);
  }

  /** A new draft copied from the published version, as its next minor version for now. */
  @Transactional
  Version newDraft(StaffAccess.Staff staff) {
    if (all().stream()
        .anyMatch(v -> v.status().equals("DRAFT") || v.status().equals("IN_REVIEW"))) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    Version base = published().orElse(null);
    if (base == null) {
      return seed(staff.id(), staff.name());
    }
    return insert(
        base.major(),
        nextMinor(base.major()),
        base.questions(),
        base.rules(),
        base.specialtyOrder(),
        base.testSets(),
        base.id(),
        staff.id(),
        staff.name());
  }

  @Transactional
  Version saveQuestions(UUID id, List<Question> questions) {
    Version v = draft(id);
    checkLocked(questions);
    Set<String> codes = new HashSet<>();
    for (Question q : questions) {
      if (!codes.add(q.code())) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    List<Question> stored = questions.stream().map(Question::withLocked).toList();
    db.sql("UPDATE hc_version SET questions_json = CAST(:q AS jsonb) WHERE id = :id")
        .param("q", write(stored))
        .param("id", id)
        .update();
    renumber(v.id(), stored);
    return require(id);
  }

  @Transactional
  Version saveScoring(UUID id, List<Rule> rules, List<String> specialtyOrder) {
    draft(id);
    db.sql(
            "UPDATE hc_version SET rules_json = CAST(:r AS jsonb), specialty_order = :o WHERE id = :id")
        .param("r", write(rules))
        .param("o", specialtyOrder.toArray(String[]::new))
        .param("id", id)
        .update();
    return require(id);
  }

  @Transactional
  Version saveTestSets(UUID id, List<TestSet> sets) {
    Version v = require(id);
    if (v.status().equals("PUBLISHED") || v.status().equals("RETIRED")) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    db.sql("UPDATE hc_version SET test_sets_json = CAST(:t AS jsonb) WHERE id = :id")
        .param("t", write(sets))
        .param("id", id)
        .update();
    return require(id);
  }

  @Transactional
  Version submit(UUID id, Set<String> knownSpecialties) {
    Version v = draft(id);
    if (!problems(v, knownSpecialties).isEmpty()) {
      throw new SolaceException(HealthCheckError.NOT_READY);
    }
    db.sql("UPDATE hc_version SET status = 'IN_REVIEW', submitted_at = :now WHERE id = :id")
        .param("now", Timestamp.from(clock.instant()))
        .param("id", id)
        .update();
    return require(id);
  }

  /** A Clinical reviewer who did not write it approves, or returns it to draft with comments. */
  @Transactional
  Version review(UUID id, StaffAccess.Staff staff, boolean approve, String comments) {
    Version v = require(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() != null) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    if (v.draftedBy().equals(staff.id())) {
      throw new SolaceException(HealthCheckError.SAME_PERSON);
    }
    if (!approve && (comments == null || comments.isBlank())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    Instant now = clock.instant();
    db.sql(
            """
            INSERT INTO hc_review (id, version_id, reviewer, reviewer_name, decision, comments, decided_at)
            VALUES (:id, :v, :r, :n, :d, :c, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("v", id)
        .param("r", staff.id())
        .param("n", staff.name())
        .param("d", approve ? "APPROVED" : "RETURNED")
        .param("c", comments)
        .param("now", Timestamp.from(now))
        .update();
    if (approve) {
      db.sql(
              """
              UPDATE hc_version SET approved_by = :r, approved_by_name = :n, approved_at = :now
               WHERE id = :id
              """)
          .param("r", staff.id())
          .param("n", staff.name())
          .param("now", Timestamp.from(now))
          .param("id", id)
          .update();
    } else {
      db.sql("UPDATE hc_version SET status = 'DRAFT', submitted_at = NULL WHERE id = :id")
          .param("id", id)
          .update();
    }
    return require(id);
  }

  /** Publishes an approved version; the old one retires. Responses keep their own version. */
  @Transactional
  Version publish(UUID id) {
    Version v = require(id);
    if (!v.status().equals("IN_REVIEW") || v.approvedBy() == null) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    Timestamp now = Timestamp.from(clock.instant());
    db.sql("UPDATE hc_version SET status = 'RETIRED', retired_at = :now WHERE status = 'PUBLISHED'")
        .param("now", now)
        .update();
    db.sql("UPDATE hc_version SET status = 'PUBLISHED', published_at = :now WHERE id = :id")
        .param("now", now)
        .param("id", id)
        .update();
    return require(id);
  }

  // --- Checks -----------------------------------------------------------------------------------

  /** What stops a draft being submitted, as stable codes for the admin screen. */
  static List<String> problems(Version v, Set<String> knownSpecialties) {
    List<String> problems = new ArrayList<>();
    Map<String, Question> byCode =
        v.questions().stream().collect(Collectors.toMap(Question::code, q -> q, (a, b) -> a));
    for (String locked : Canon.locked().keySet()) {
      if (!byCode.containsKey(locked)) {
        problems.add("missing:" + locked);
      }
    }
    for (Question q : v.questions()) {
      if (blank(q.textEn()) || blank(q.textFr())) {
        problems.add("text:" + q.code());
      }
      if (q.options() == null || q.options().size() < 2) {
        problems.add("options:" + q.code());
      } else if (q.options().stream().anyMatch(o -> blank(o.labelEn()) || blank(o.labelFr()))) {
        problems.add("text:" + q.code());
      }
      if (q.showIf() != null
          && q.showIf().question() != null
          && !byCode.containsKey(q.showIf().question())) {
        problems.add("showIf:" + q.code());
      }
    }
    if (v.rules().isEmpty()) {
      problems.add("rules");
    }
    for (Rule r : v.rules()) {
      boolean valid =
          !blank(r.reasonEn())
              && !blank(r.reasonFr())
              && wordingAllowed(r.reasonEn())
              && wordingAllowed(r.reasonFr())
              && knownSpecialties.contains(r.specialty())
              && r.when() != null
              && !r.when().isEmpty()
              && r.when().stream()
                  .allMatch(
                      w ->
                          (w.question() != null
                                  && byCode.containsKey(w.question())
                                  && w.anyOf() != null
                                  && !w.anyOf().isEmpty())
                              || (w.screener() != null
                                  && (w.atLeast() != null || w.atMost() != null)));
      if (!valid) {
        problems.add("rule:" + r.code());
      }
    }
    Set<String> scored = v.rules().stream().map(Rule::specialty).collect(Collectors.toSet());
    if (!v.specialtyOrder().containsAll(scored)) {
      problems.add("specialtyOrder");
    }
    return problems;
  }

  /**
   * Reasons describe what the person said; wording that names a condition, a severity or a
   * prediction is refused (HealthCheckScoring.dc.html). "Not a diagnosis" is allowed.
   */
  private static final List<java.util.regex.Pattern> BLOCKED =
      List.of(
          java.util.regex.Pattern.compile("\\bdiagnos"),
          java.util.regex.Pattern.compile("\\byou have\\b"),
          java.util.regex.Pattern.compile("\\bvous avez (un|une|le|la|des)\\b"),
          java.util.regex.Pattern.compile(
              "\\b(disorder|disease|syndrome|illness|trouble|maladie|severe|grave|predict|triage)\\b"),
          java.util.regex.Pattern.compile(
              "\\b(depression|depressed|depressive|bipolar|depressif|bipolaire)\\b"));

  static boolean wordingAllowed(String text) {
    String t =
        java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace("not a diagnosis", " ")
            .replace("pas un diagnostic", " ");
    return BLOCKED.stream().noneMatch(p -> p.matcher(t).find());
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** The safety question and screener items must be exactly Solace's. */
  private static void checkLocked(List<Question> questions) {
    Map<String, Question> canon = Canon.locked();
    for (Question q : questions) {
      Question fixed = canon.get(q.code());
      boolean claimsLocked = q.kind() == Kind.SAFETY || q.kind() == Kind.SCREENER;
      if (fixed == null) {
        if (claimsLocked) {
          throw new SolaceException(HealthCheckError.LOCKED);
        }
        continue;
      }
      if (!same(fixed, q)) {
        throw new SolaceException(HealthCheckError.LOCKED);
      }
    }
    Set<String> present = questions.stream().map(Question::code).collect(Collectors.toSet());
    if (!present.containsAll(canon.keySet())) {
      throw new SolaceException(HealthCheckError.LOCKED);
    }
  }

  private static boolean same(Question a, Question b) {
    return a.kind() == b.kind()
        && Objects.equals(a.textEn(), b.textEn())
        && Objects.equals(a.textFr(), b.textFr())
        && Objects.equals(a.screener(), b.screener())
        && Objects.equals(a.item(), b.item())
        && Objects.equals(a.options(), b.options())
        && Objects.equals(a.showIf(), b.showIf());
  }

  // --- Plumbing ---------------------------------------------------------------------------------

  private Version draft(UUID id) {
    Version v = require(id);
    if (!v.status().equals("DRAFT")) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    return v;
  }

  /** Major when the set of questions or options differs from the base version, else minor. */
  private void renumber(UUID id, List<Question> questions) {
    Version v = require(id);
    if (v.baseVersionId() == null) {
      return;
    }
    Version base = require(v.baseVersionId());
    boolean major = !shape(base.questions()).equals(shape(questions));
    int nextMajor = major ? base.major() + 1 : base.major();
    if (v.major() == nextMajor) {
      return;
    }
    int nextMinor = nextMinor(nextMajor);
    db.sql("UPDATE hc_version SET major = :ma, minor = :mi WHERE id = :id")
        .param("ma", nextMajor)
        .param("mi", nextMinor)
        .param("id", id)
        .update();
  }

  private static Set<String> shape(List<Question> questions) {
    Set<String> shape = new HashSet<>();
    for (Question q : questions) {
      shape.add(q.code());
      for (var o : q.options()) {
        shape.add(q.code() + "/" + o.code());
      }
    }
    return shape;
  }

  private int nextMinor(int major) {
    Integer max =
        db.sql("SELECT max(minor) FROM hc_version WHERE major = :m")
            .param("m", major)
            .query(Integer.class)
            .single();
    return max == null ? 0 : max + 1;
  }

  private Version insert(
      int major,
      int minor,
      List<Question> questions,
      List<Rule> rules,
      List<String> order,
      List<TestSet> sets,
      UUID base,
      String by,
      String byName) {
    UUID id = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO hc_version (id, major, minor, status, questions_json, rules_json, specialty_order,
                                    test_sets_json, base_version_id, drafted_by, drafted_by_name, created_at)
            VALUES (:id, :ma, :mi, 'DRAFT', CAST(:q AS jsonb), CAST(:r AS jsonb), :o, CAST(:t AS jsonb),
                    :b, :by, :n, :now)
            """)
        .param("id", id)
        .param("ma", major)
        .param("mi", minor)
        .param("q", write(questions))
        .param("r", write(rules))
        .param("o", order.toArray(String[]::new))
        .param("t", write(sets))
        .param("b", base)
        .param("by", by)
        .param("n", byName)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return require(id);
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String text, TypeReference<T> type) {
    try {
      return json.readValue(text, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Version row(ResultSet rs, int n) throws SQLException {
    Timestamp published = rs.getTimestamp("published_at");
    return new Version(
        rs.getObject("id", UUID.class),
        rs.getInt("major"),
        rs.getInt("minor"),
        rs.getString("status"),
        read(rs.getString("questions_json"), QUESTIONS),
        read(rs.getString("rules_json"), RULES),
        Arrays.asList((String[]) rs.getArray("specialty_order").getArray()),
        read(rs.getString("test_sets_json"), TEST_SETS),
        rs.getObject("base_version_id", UUID.class),
        rs.getString("drafted_by"),
        rs.getString("drafted_by_name"),
        rs.getString("approved_by"),
        rs.getString("approved_by_name"),
        published == null ? null : published.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
