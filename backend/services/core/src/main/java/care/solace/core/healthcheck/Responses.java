package care.solace.core.healthcheck;

import care.solace.core.admin.PlatformSettings;
import care.solace.core.bookings.HealthCheckShared;
import care.solace.core.crisis.CallbackContacts;
import care.solace.core.crisis.CrisisRaising;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Engine.Score;
import care.solace.core.identity.PersonalData;
import care.solace.core.privacy.ConsentCheck;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.jobs.Job;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health check responses (System Design, Health check; D55). Answers, totals and the suggestion are
 * sealed; only the suggested specialty code is kept in the clear, for counts. A seeker's response
 * is theirs by account; a guest's by a token only the phone holds.
 */
@Service
class Responses implements CallbackContacts, PersonalData {

  static final String SOURCE = "HEALTH_CHECK";
  private static final Logger LOG = LoggerFactory.getLogger(Responses.class);

  /** What is sealed: the person's answers and what was worked out from them. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record State(
      Map<String, List<String>> answers,
      List<Score> scores,
      String specialty,
      String alsoFit,
      List<String> reasons,
      List<String> alsoFitReasons) {

    static State empty() {
      return new State(new LinkedHashMap<>(), null, null, null, null, null);
    }
  }

  record Preferences(String sessionLanguage, String modality, Long maxPrice, String gender) {}

  record Response(
      UUID id,
      UUID versionId,
      UUID accountId,
      String status,
      boolean safetyStopped,
      State state,
      Preferences preferences,
      Instant createdAt,
      Instant completedAt) {}

  record Progress(String status, List<String> next) {}

  record Started(UUID id, String token) {}

  private final JdbcClient db;
  private final Versions versions;
  private final Sealer sealer;
  private final ObjectMapper json;
  private final ConsentCheck consents;
  private final CrisisRaising crisis;
  private final PlatformSettings settings;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  Responses(
      JdbcClient db,
      Versions versions,
      Sealer sealer,
      ObjectMapper json,
      ConsentCheck consents,
      CrisisRaising crisis,
      PlatformSettings settings,
      Clock clock) {
    this.db = db;
    this.versions = versions;
    this.sealer = sealer;
    this.json = json;
    this.consents = consents;
    this.crisis = crisis;
    this.settings = settings;
    this.clock = clock;
  }

  // --- Starting and finding ---------------------------------------------------------------------

  /** A seeker needs HEALTH_CHECK consent; a guest gives it on the start screen. */
  @Transactional
  Started start(Optional<UUID> account, String consentVersion) {
    Versions.Version v =
        versions.published().orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (account.isPresent() && !consents.granted(account.get(), "HEALTH_CHECK")) {
      throw new SolaceException(HealthCheckError.CONSENT_NEEDED);
    }
    String token = null;
    if (account.isEmpty()) {
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    UUID id = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO hc_response (id, version_id, account_id, token_hash, consent_version, sealed,
                                     status, created_at)
            VALUES (:id, :v, :a, :t, :c, :s, 'IN_PROGRESS', :now)
            """)
        .param("id", id)
        .param("v", v.id())
        .param("a", account.orElse(null))
        .param("t", token == null ? null : hash(token))
        .param("c", consentVersion)
        .param("s", seal(State.empty()))
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return new Started(id, token);
  }

  /** The response, if the caller holds it: the account that owns it, or the guest's token. */
  Response require(UUID id, Optional<UUID> account, String token) {
    Response r =
        db.sql("SELECT * FROM hc_response WHERE id = :id")
            .param("id", id)
            .query((rs, n) -> row(rs))
            .optional()
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    boolean mine =
        (r.accountId() != null && account.isPresent() && r.accountId().equals(account.get()))
            || (token != null && hash(token).equals(tokenHash(id)));
    if (!mine) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    return r;
  }

  Optional<Response> latest(UUID account) {
    return db.sql(
            """
            SELECT * FROM hc_response WHERE account_id = :a AND status = 'COMPLETED'
             ORDER BY completed_at DESC LIMIT 1
            """)
        .param("a", account)
        .query((rs, n) -> row(rs))
        .optional();
  }

  boolean stale(Response r) {
    Duration window = settings.duration("solace.health-check.retake-after", Duration.ofDays(90));
    return r.completedAt() != null && r.completedAt().plus(window).isBefore(clock.instant());
  }

  // --- Answering --------------------------------------------------------------------------------

  /**
   * Saves every answer so far. The first time the locked safety rule holds, the check stops and
   * urgent help opens; for a signed-in seeker the on-call specialist is alerted at once.
   */
  @Transactional
  Progress answer(Response r, Map<String, List<String>> answers, String language) {
    if (!r.status().equals("IN_PROGRESS")) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    Versions.Version v = versions.require(r.versionId());
    validate(v.questions(), answers);
    Map<String, List<String>> kept = new LinkedHashMap<>();
    for (Question q : Engine.visible(v.questions(), answers)) {
      if (answers.containsKey(q.code())) {
        kept.put(q.code(), List.copyOf(answers.get(q.code())));
      }
    }
    State state = new State(kept, null, null, null, null, null);
    var stop = Engine.safetyStop(v.questions(), kept);
    boolean firstStop = stop.isPresent() && !r.safetyStopped();
    db.sql(
            "UPDATE hc_response SET sealed = :s, safety_stopped = safety_stopped OR :stop WHERE id = :id")
        .param("s", seal(state))
        .param("stop", stop.isPresent())
        .param("id", r.id())
        .update();
    if (firstStop && r.accountId() != null) {
      crisis.raise(SOURCE, r.id(), r.accountId(), false, stop.get(), language);
    }
    List<String> next = Engine.next(v.questions(), kept);
    return new Progress(firstStop ? "SAFETY_STOP" : next.isEmpty() ? "READY" : "IN_PROGRESS", next);
  }

  /** A guest leaves a number after the safety answer; the alert goes to the on-call specialist. */
  @Transactional
  void callback(Response r, String phone, String language) {
    if (!r.safetyStopped()) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    db.sql("UPDATE hc_response SET callback_phone = :p WHERE id = :id")
        .param("p", phone)
        .param("id", r.id())
        .update();
    var category =
        Engine.safetyStop(versions.require(r.versionId()).questions(), r.state().answers())
            .orElse("UNKNOWN");
    crisis.raise(
        SOURCE,
        r.id(),
        r.accountId() != null ? r.accountId() : r.id(),
        r.accountId() == null,
        category,
        language);
  }

  /** Every shown question answered: the suggestion, which preferences never change. */
  @Transactional
  Response complete(Response r, Preferences preferences) {
    if (!r.status().equals("IN_PROGRESS")) {
      throw new SolaceException(HealthCheckError.WRONG_STATE);
    }
    Versions.Version v = versions.require(r.versionId());
    Map<String, List<String>> answers = r.state().answers();
    if (!Engine.next(v.questions(), answers).isEmpty()) {
      throw new SolaceException(HealthCheckError.NOT_COMPLETE);
    }
    int within = (int) settings.number("solace.health-check.also-fit-within", 1);
    var suggestion = Engine.suggest(v.questions(), v.rules(), v.specialtyOrder(), answers, within);
    State done =
        new State(
            answers,
            Engine.scores(v.questions(), answers),
            suggestion.specialty(),
            suggestion.alsoFit(),
            suggestion.reasons().stream().map(Definition.Rule::code).toList(),
            suggestion.alsoFitReasons().stream().map(Definition.Rule::code).toList());
    db.sql(
            """
            UPDATE hc_response SET sealed = :s, status = 'COMPLETED', completed_at = :now,
                   specialty_code = :sp, preferences = CAST(:p AS jsonb)
             WHERE id = :id
            """)
        .param("s", seal(done))
        .param("now", Timestamp.from(clock.instant()))
        .param("sp", suggestion.specialty())
        .param("p", write(preferences))
        .param("id", r.id())
        .update();
    return reload(r.id());
  }

  /** A guest who signed up keeps their answers. */
  @Transactional
  void claim(Response r, UUID account) {
    db.sql(
            "UPDATE hc_response SET account_id = :a, token_hash = NULL WHERE id = :id AND account_id IS NULL")
        .param("a", account)
        .param("id", r.id())
        .update();
  }

  /** "Share my answers with the specialist I book" (Matches.dc.html). */
  @EventListener
  void on(HealthCheckShared share) {
    int mine =
        db.sql(
                "SELECT count(*) FROM hc_response WHERE id = :r AND account_id = :a AND status = 'COMPLETED'")
            .param("r", share.responseId())
            .param("a", share.seekerAccountId())
            .query(Integer.class)
            .single();
    if (mine == 0) {
      return;
    }
    db.sql(
            """
            INSERT INTO hc_share (booking_id, response_id, shared_at) VALUES (:b, :r, :now)
            ON CONFLICT (booking_id) DO NOTHING
            """)
        .param("b", share.bookingId())
        .param("r", share.responseId())
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  Optional<Response> sharedWith(UUID bookingId) {
    return db.sql(
            "SELECT r.* FROM hc_share s JOIN hc_response r ON r.id = s.response_id WHERE s.booking_id = :b")
        .param("b", bookingId)
        .query((rs, n) -> row(rs))
        .optional();
  }

  // --- Counts for admin screen 28 ---------------------------------------------------------------

  record Counts(int completions, int safetyStops, Map<String, Integer> specialties) {}

  Counts counts(UUID versionId) {
    int completions =
        db.sql("SELECT count(*) FROM hc_response WHERE version_id = :v AND status = 'COMPLETED'")
            .param("v", versionId)
            .query(Integer.class)
            .single();
    int stops =
        db.sql("SELECT count(*) FROM hc_response WHERE version_id = :v AND safety_stopped")
            .param("v", versionId)
            .query(Integer.class)
            .single();
    Map<String, Integer> specialties = new LinkedHashMap<>();
    db.sql(
            """
            SELECT specialty_code, count(*) AS n FROM hc_response
             WHERE version_id = :v AND specialty_code IS NOT NULL
             GROUP BY specialty_code ORDER BY n DESC
            """)
        .param("v", versionId)
        .query((rs, n) -> Map.entry(rs.getString("specialty_code"), rs.getInt("n")))
        .list()
        .forEach(e -> specialties.put(e.getKey(), e.getValue()));
    return new Counts(completions, stops, specialties);
  }

  record Stats(int completed, int started, int safetyStops, Map<String, Integer> suggestions) {}

  /** Counts for admin screen 28: the last 7 days, and suggestions over 30. No answers. */
  Stats stats() {
    Instant now = clock.instant();
    Timestamp week = Timestamp.from(now.minus(Duration.ofDays(7)));
    int completed =
        db.sql("SELECT count(*) FROM hc_response WHERE completed_at >= :t")
            .param("t", week)
            .query(Integer.class)
            .single();
    int started =
        db.sql("SELECT count(*) FROM hc_response WHERE created_at >= :t")
            .param("t", week)
            .query(Integer.class)
            .single();
    int stops =
        db.sql("SELECT count(*) FROM hc_response WHERE safety_stopped AND created_at >= :t")
            .param("t", week)
            .query(Integer.class)
            .single();
    Map<String, Integer> suggestions = new LinkedHashMap<>();
    db.sql(
            """
            SELECT specialty_code, count(*) AS n FROM hc_response
             WHERE completed_at >= :t AND specialty_code IS NOT NULL
             GROUP BY specialty_code ORDER BY n DESC
            """)
        .param("t", Timestamp.from(now.minus(Duration.ofDays(30))))
        .query((rs, n) -> Map.entry(rs.getString("specialty_code"), rs.getInt("n")))
        .list()
        .forEach(e -> suggestions.put(e.getKey(), e.getValue()));
    return new Stats(completed, started, stops, suggestions);
  }

  // --- Retention, export and erasure ------------------------------------------------------------

  /** Unclaimed guest responses go after [30] days (System Design, Guests and storage). */
  @Job(
      name = "core.health-check.forget-unclaimed",
      description = "Deletes guest health check answers never claimed",
      cron = "0 15 3 * * *",
      zone = "Africa/Douala")
  void forgetUnclaimed() {
    try {
      int gone = forgetUnclaimedNow();
      if (gone > 0) {
        LOG.info("Deleted {} unclaimed health check responses", gone);
      }
    } catch (RuntimeException e) {
      LOG.warn("Deleting unclaimed health check responses failed", e);
    }
  }

  int forgetUnclaimedNow() {
    Duration keep =
        settings.duration("solace.health-check.unclaimed-retention", Duration.ofDays(30));
    return db.sql("DELETE FROM hc_response WHERE account_id IS NULL AND created_at < :before")
        .param("before", Timestamp.from(clock.instant().minus(keep)))
        .update();
  }

  @Override
  public Optional<String> phone(String source, UUID sourceId) {
    if (!SOURCE.equals(source)) {
      return Optional.empty();
    }
    return db.sql(
            "SELECT callback_phone FROM hc_response WHERE id = :id AND callback_phone IS NOT NULL")
        .param("id", sourceId)
        .query(String.class)
        .optional();
  }

  @Override
  public String section() {
    return "healthCheck";
  }

  @Override
  public Object export(UUID accountId) {
    List<Map<String, Object>> out = new ArrayList<>();
    db.sql("SELECT * FROM hc_response WHERE account_id = :a ORDER BY created_at")
        .param("a", accountId)
        .query((rs, n) -> row(rs))
        .list()
        .forEach(
            r -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("startedAt", r.createdAt());
              item.put("completedAt", r.completedAt());
              item.put("answers", r.state().answers());
              item.put("suggestion", r.state().specialty());
              out.add(item);
            });
    return out.isEmpty() ? null : out;
  }

  @Override
  public void erase(UUID accountId) {
    db.sql("DELETE FROM hc_response WHERE account_id = :a").param("a", accountId).update();
  }

  // --- Plumbing ---------------------------------------------------------------------------------

  private Response reload(UUID id) {
    return db.sql("SELECT * FROM hc_response WHERE id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .single();
  }

  /** Answers name real questions and options; a single-answer question takes one. */
  private static void validate(List<Question> questions, Map<String, List<String>> answers) {
    Map<String, Question> byCode = new LinkedHashMap<>();
    questions.forEach(q -> byCode.put(q.code(), q));
    for (var e : answers.entrySet()) {
      Question q = byCode.get(e.getKey());
      if (q == null || e.getValue() == null || (!q.multi() && e.getValue().size() > 1)) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      for (String code : e.getValue()) {
        if (q.options().stream().noneMatch(o -> o.code().equals(code))) {
          throw new SolaceException(CommonError.REQUEST_INVALID);
        }
      }
    }
  }

  private String tokenHash(UUID id) {
    return db.sql("SELECT token_hash FROM hc_response WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse("");
  }

  private static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] seal(State state) {
    return sealer.seal(write(state).getBytes(StandardCharsets.UTF_8));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Response row(java.sql.ResultSet rs) throws java.sql.SQLException {
    try {
      State state =
          json.readValue(
              new String(sealer.open(rs.getBytes("sealed")), StandardCharsets.UTF_8), State.class);
      String prefs = rs.getString("preferences");
      Timestamp completed = rs.getTimestamp("completed_at");
      return new Response(
          rs.getObject("id", UUID.class),
          rs.getObject("version_id", UUID.class),
          rs.getObject("account_id", UUID.class),
          rs.getString("status"),
          rs.getBoolean("safety_stopped"),
          state,
          prefs == null ? null : json.readValue(prefs, Preferences.class),
          rs.getTimestamp("created_at").toInstant(),
          completed == null ? null : completed.toInstant());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
