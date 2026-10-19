package care.solace.ai.features;

import care.solace.ai.gateway.Feature;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI features, their prompt and policy versions, and the switch (admin screen 26; D56). A feature
 * calls a model only while it is on, and it can be switched on only while its live version's latest
 * evaluation passed in English and French. A draft becomes live only with a passing evaluation and
 * a Clinical reviewer's sign-off.
 */
@Service
public class Features {

  public record State(
      Feature feature, boolean enabled, String liveVersion, String changedBy, Instant changedAt) {}

  public record PromptVersion(
      UUID id,
      Feature feature,
      String version,
      String status,
      String system,
      String policy,
      String createdBy,
      Instant createdAt,
      String signedOffBy,
      Instant promotedAt) {

    /** What the model is told: the feature's instructions, then the policy that binds them. */
    public String systemPrompt() {
      return system + "\n\n" + policy;
    }
  }

  private final JdbcClient db;
  private final Evaluations evaluations;
  private final Clock clock;

  Features(JdbcClient db, Evaluations evaluations, Clock clock) {
    this.db = db;
    this.evaluations = evaluations;
    this.clock = clock;
  }

  public List<State> all() {
    return db.sql("SELECT * FROM ai_feature ORDER BY code").query(Features::state).list();
  }

  public State state(Feature feature) {
    return db.sql("SELECT * FROM ai_feature WHERE code = :c")
        .param("c", feature.name())
        .query(Features::state)
        .single();
  }

  /** The live version, when the feature is on; empty means "do not call a model". */
  public Optional<PromptVersion> live(Feature feature) {
    State s = state(feature);
    if (!s.enabled() || s.liveVersion() == null) {
      return Optional.empty();
    }
    return version(feature, s.liveVersion());
  }

  public Optional<PromptVersion> version(Feature feature, String version) {
    return db.sql("SELECT * FROM prompt_version WHERE feature = :f AND version = :v")
        .param("f", feature.name())
        .param("v", version)
        .query(Features::prompt)
        .optional();
  }

  public PromptVersion require(UUID id) {
    return db.sql("SELECT * FROM prompt_version WHERE id = :id")
        .param("id", id)
        .query(Features::prompt)
        .optional()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  public List<PromptVersion> versions(Feature feature) {
    return db.sql("SELECT * FROM prompt_version WHERE feature = :f ORDER BY created_at DESC")
        .param("f", feature.name())
        .query(Features::prompt)
        .list();
  }

  /** A new draft: the feature's instructions and the policy that binds them. */
  @Transactional
  public PromptVersion draft(Feature feature, String system, String policy, String by) {
    int n = versions(feature).size();
    String version = "1." + n;
    UUID id = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO prompt_version (id, feature, version, status, system_text, policy_text,
                                        created_by, created_at)
            VALUES (:id, :f, :v, 'DRAFT', :s, :p, :by, :now)
            """)
        .param("id", id)
        .param("f", feature.name())
        .param("v", version)
        .param("s", system)
        .param("p", policy)
        .param("by", by)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return require(id);
  }

  /** A Clinical reviewer signs off the graded sample of a draft's evaluation. */
  @Transactional
  public PromptVersion signOff(UUID id, String clinicalReviewer) {
    PromptVersion v = require(id);
    if (!v.status().equals("DRAFT") || !evaluations.passed(v.id())) {
      throw new SolaceException(FeatureError.NOT_EVALUATED);
    }
    db.sql("UPDATE prompt_version SET signed_off_by = :by WHERE id = :id")
        .param("by", clinicalReviewer)
        .param("id", id)
        .update();
    return require(id);
  }

  /**
   * Makes a draft live: its latest evaluation passed in both languages and a Clinical reviewer
   * signed it off. The old live version retires; v1 stays live until the new one is promoted.
   */
  @Transactional
  public PromptVersion promote(UUID id, String by) {
    PromptVersion v = require(id);
    if (!v.status().equals("DRAFT") || !evaluations.passed(v.id())) {
      throw new SolaceException(FeatureError.NOT_EVALUATED);
    }
    if (v.signedOffBy() == null) {
      throw new SolaceException(FeatureError.SIGN_OFF_NEEDED);
    }
    Timestamp now = Timestamp.from(clock.instant());
    db.sql("UPDATE prompt_version SET status = 'RETIRED' WHERE feature = :f AND status = 'LIVE'")
        .param("f", v.feature().name())
        .update();
    db.sql("UPDATE prompt_version SET status = 'LIVE', promoted_at = :now WHERE id = :id")
        .param("now", now)
        .param("id", id)
        .update();
    db.sql(
            "UPDATE ai_feature SET live_prompt_version = :v, changed_by = :by, changed_at = :now WHERE code = :f")
        .param("v", v.version())
        .param("by", by)
        .param("now", now)
        .param("f", v.feature().name())
        .update();
    return require(id);
  }

  /** On only with a live version whose latest evaluation passed in EN and FR. */
  @Transactional
  public State switchTo(Feature feature, boolean on, String by) {
    State s = state(feature);
    if (on) {
      var live =
          s.liveVersion() == null
              ? Optional.<PromptVersion>empty()
              : version(feature, s.liveVersion());
      if (live.isEmpty() || !evaluations.passed(live.get().id())) {
        throw new SolaceException(FeatureError.NOT_EVALUATED);
      }
    }
    db.sql(
            "UPDATE ai_feature SET enabled = :e, changed_by = :by, changed_at = :now WHERE code = :f")
        .param("e", on)
        .param("by", by)
        .param("now", Timestamp.from(clock.instant()))
        .param("f", feature.name())
        .update();
    return state(feature);
  }

  private static State state(ResultSet rs, int n) throws SQLException {
    Timestamp changed = rs.getTimestamp("changed_at");
    return new State(
        Feature.valueOf(rs.getString("code")),
        rs.getBoolean("enabled"),
        rs.getString("live_prompt_version"),
        rs.getString("changed_by"),
        changed == null ? null : changed.toInstant());
  }

  private static PromptVersion prompt(ResultSet rs, int n) throws SQLException {
    Timestamp promoted = rs.getTimestamp("promoted_at");
    return new PromptVersion(
        rs.getObject("id", UUID.class),
        Feature.valueOf(rs.getString("feature")),
        rs.getString("version"),
        rs.getString("status"),
        rs.getString("system_text"),
        rs.getString("policy_text"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("signed_off_by"),
        promoted == null ? null : promoted.toInstant());
  }
}
