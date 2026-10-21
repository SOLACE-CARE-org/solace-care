package care.solace.core.crisis;

import care.solace.core.notifications.Delivery;
import care.solace.core.reviews.FlaggedReviews;
import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Crisis alerts (Admin Portal Spec, Crisis alert; D11, D15, D39): each ai.risk.flagged becomes one
 * alert; the on-call responder gets an SMS at once, the backup after 5 minutes without an
 * acknowledgement, the escalation list after 10. Messages carry no personal details.
 */
@Service
class CrisisAlerts implements CrisisSummaries, CrisisRaising {

  static final String CONSUMER = "core.crisis-alerts";

  /** D11: 5 minutes to the backup, 5 more to the Super admins. Decisions, not settings. */
  static final Duration TO_BACKUP = Duration.ofMinutes(5);

  static final Duration TO_ADMINS = Duration.ofMinutes(10);

  private static final Logger LOG = LoggerFactory.getLogger(CrisisAlerts.class);
  private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");

  private final JdbcClient db;
  private final IdempotentConsumer once;
  private final RiskDecisions risks;
  private final FlaggedReviews reviews;
  private final Delivery delivery;
  private final care.solace.core.notifications.StaffPush push;
  private final Clock clock;

  CrisisAlerts(
      JdbcClient db,
      IdempotentConsumer once,
      RiskDecisions risks,
      FlaggedReviews reviews,
      Delivery delivery,
      care.solace.core.notifications.StaffPush push,
      Clock clock) {
    this.db = db;
    this.once = once;
    this.risks = risks;
    this.reviews = reviews;
    this.delivery = delivery;
    this.push = push;
    this.clock = clock;
  }

  record Alert(
      UUID id,
      UUID decisionId,
      String source,
      UUID sourceId,
      UUID subjectId,
      boolean guest,
      String category,
      String language,
      String status,
      Instant raisedAt,
      Instant escalatedBackupAt,
      Instant escalatedAdminsAt,
      String acknowledgedBy,
      String acknowledgedByName,
      Instant acknowledgedAt,
      String outcome,
      String note,
      Instant closedAt) {

    /** "#4821", or "G-2198" for a guest, as the queue names people. */
    String personRef() {
      String digits = String.format("%04d", Math.floorMod(subjectId.hashCode(), 10_000));
      return guest ? "G-" + digits : "#" + digits;
    }
  }

  record Shift(
      UUID id,
      String staffId,
      String name,
      String phone,
      String language,
      String role,
      Instant startsAt,
      Instant endsAt) {}

  /** ai.risk.flagged v1, Core's own view of the payload (D2). */
  record Flagged(
      UUID decisionId,
      String source,
      UUID sourceId,
      String classifierVersion,
      String policyVersion) {}

  // --- Raising ----------------------------------------------------------------------------------

  @KafkaListener(topics = "ai.risk")
  void on(EventEnvelope event) {
    if (!"ai.risk.flagged".equals(event.type())) {
      return;
    }
    Flagged flagged = event.payloadAs(Flagged.class);
    UUID subject = UUID.fromString(event.subjectId());
    // The category is read outside the consumer's transaction: a remote call.
    String category = risks.category(flagged.decisionId()).orElse("UNKNOWN");
    once.handle(CONSUMER, event, () -> raise(flagged, subject, category));
  }

  void raise(Flagged flagged, UUID subjectId, String category) {
    Instant now = clock.instant();
    boolean guest = false;
    String language = null;
    if ("REVIEW".equals(flagged.source())) {
      var review = reviews.find(flagged.sourceId());
      reviews.flag(flagged.sourceId());
      guest = review.map(FlaggedReviews.Flagged::guest).orElse(false);
      language = review.map(FlaggedReviews.Flagged::language).orElse(null);
    }
    insert(
        flagged.decisionId(),
        flagged.source(),
        flagged.sourceId(),
        subjectId,
        guest,
        category,
        language,
        now);
  }

  @Override
  public void raise(
      String source,
      UUID sourceId,
      UUID subjectId,
      boolean guest,
      String category,
      String language) {
    // The source item is its own decision: one alert per health check response.
    insert(sourceId, source, sourceId, subjectId, guest, category, language, clock.instant());
  }

  @Override
  public void raise(
      String source,
      UUID sourceId,
      UUID subjectId,
      boolean guest,
      String category,
      String language,
      String note) {
    raise(source, sourceId, subjectId, guest, category, language);
    if (note != null && !note.isBlank()) {
      db.sql(
              "UPDATE crisis_alert SET concern_note = :n WHERE decision_id = :d"
                  + " AND concern_note IS NULL")
          .param("n", note.strip())
          .param("d", sourceId)
          .update();
    }
  }

  /** The note a specialist gave with a session safety concern. */
  java.util.Optional<String> note(UUID alertId) {
    return db.sql(
            "SELECT concern_note FROM crisis_alert WHERE id = :id AND concern_note IS NOT NULL")
        .param("id", alertId)
        .query(String.class)
        .optional();
  }

  private void insert(
      UUID decisionId,
      String source,
      UUID sourceId,
      UUID subjectId,
      boolean guest,
      String category,
      String language,
      Instant now) {
    int inserted =
        db.sql(
                """
                INSERT INTO crisis_alert (id, decision_id, source, source_id, subject_id, guest,
                                          category, language, status, raised_at)
                VALUES (:id, :d, :src, :sid, :sub, :g, :c, :l, 'OPEN', :now)
                ON CONFLICT (decision_id) DO NOTHING
                """)
            .param("id", UUID.randomUUID())
            .param("d", decisionId)
            .param("src", source)
            .param("sid", sourceId)
            .param("sub", subjectId)
            .param("g", guest)
            .param("c", category)
            .param("l", language)
            .param("now", Timestamp.from(now))
            .update();
    if (inserted == 1) {
      List<Shift> primary = onCall("PRIMARY", now);
      if (primary.isEmpty()) {
        // Nobody on call: the backup and the escalation list hear at once.
        LOG.warn("Crisis alert raised with nobody on call");
        notify(onCall("BACKUP", now), "sms.crisis-backup", category, now);
        notify(onCall("ESCALATION", now), "sms.crisis-admins", category, now);
      } else {
        notify(primary, "sms.crisis-alert", category, now);
      }
    }
  }

  // --- Escalating -------------------------------------------------------------------------------

  @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
  void escalate() {
    try {
      escalateNow(clock.instant());
    } catch (RuntimeException e) {
      LOG.warn("Crisis escalation failed", e);
    }
  }

  void escalateNow(Instant now) {
    for (Alert a : unacknowledged()) {
      if (a.escalatedBackupAt() == null && !a.raisedAt().plus(TO_BACKUP).isAfter(now)) {
        if (mark(a.id(), "escalated_backup_at", now)) {
          notify(onCall("BACKUP", now), "sms.crisis-backup", a.category(), a.raisedAt());
        }
      }
      if (a.escalatedAdminsAt() == null && !a.raisedAt().plus(TO_ADMINS).isAfter(now)) {
        if (mark(a.id(), "escalated_admins_at", now)) {
          notify(onCall("ESCALATION", now), "sms.crisis-admins", a.category(), a.raisedAt());
        }
      }
    }
  }

  private boolean mark(UUID id, String column, Instant now) {
    return db.sql(
                "UPDATE crisis_alert SET "
                    + column
                    + " = :now WHERE id = :id AND "
                    + column
                    + " IS NULL AND status = 'OPEN'")
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  private void pushStaff(List<Shift> people, String key, String category, Instant raisedAt) {
    for (Shift s : people) {
      // Worded in the language the person chose for crisis messages on the rota.
      Locale locale = "fr".equals(s.language()) ? Locale.FRENCH : Locale.ENGLISH;
      try {
        push.send(
            List.of(s.staffId()),
            key,
            true,
            delivery.text("category." + category, locale),
            DateTimeFormatter.ofPattern("HH:mm").format(raisedAt.atZone(DOUALA)));
      } catch (RuntimeException e) {
        LOG.warn("Crisis push to on-call shift {} failed", s.id(), e);
      }
    }
  }

  private void notify(List<Shift> people, String key, String category, Instant raisedAt) {
    // Browser push too, to whoever on the shift turned it on (D47); SMS stays the one that counts.
    pushStaff(people, key.replace("sms.", "push.staff."), category, raisedAt);
    for (Shift s : people) {
      Locale locale = "fr".equals(s.language()) ? Locale.FRENCH : Locale.ENGLISH;
      String text =
          delivery.text(
              key,
              locale,
              delivery.text("category." + category, locale),
              DateTimeFormatter.ofPattern("HH:mm").format(raisedAt.atZone(DOUALA)));
      try {
        delivery.sms(s.phone(), text);
      } catch (RuntimeException e) {
        LOG.warn("Crisis SMS to on-call shift {} failed", s.id(), e);
      }
    }
  }

  // --- Reading and acting -----------------------------------------------------------------------

  List<Alert> list(String status) {
    return db.sql("SELECT * FROM crisis_alert WHERE status = :s ORDER BY raised_at LIMIT 100")
        .param("s", status)
        .query(CrisisAlerts::alert)
        .list();
  }

  List<Alert> unacknowledged() {
    return db.sql("SELECT * FROM crisis_alert WHERE status = 'OPEN' ORDER BY raised_at")
        .query(CrisisAlerts::alert)
        .list();
  }

  Optional<Alert> find(UUID id) {
    return db.sql("SELECT * FROM crisis_alert WHERE id = :id")
        .param("id", id)
        .query(CrisisAlerts::alert)
        .optional();
  }

  boolean acknowledge(UUID id, String by, String byName) {
    return db.sql(
                """
                UPDATE crisis_alert SET status = 'IN_PROGRESS', acknowledged_by = :by,
                       acknowledged_by_name = :name, acknowledged_at = :now
                 WHERE id = :id AND status = 'OPEN'
                """)
            .param("id", id)
            .param("by", by)
            .param("name", byName)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        == 1;
  }

  boolean close(UUID id, String outcome, String note, String by, String byName) {
    return db.sql(
                """
                UPDATE crisis_alert SET status = 'CLOSED', outcome = :o, note = :n, closed_by = :by,
                       closed_at = :now,
                       acknowledged_by = coalesce(acknowledged_by, :by),
                       acknowledged_by_name = coalesce(acknowledged_by_name, :name),
                       acknowledged_at = coalesce(acknowledged_at, :now)
                 WHERE id = :id AND status <> 'CLOSED'
                """)
            .param("id", id)
            .param("o", outcome)
            .param("n", note)
            .param("by", by)
            .param("name", byName)
            .param("now", Timestamp.from(clock.instant()))
            .update()
        == 1;
  }

  int count(String where) {
    return db.sql("SELECT count(*) FROM crisis_alert WHERE " + where).query(Integer.class).single();
  }

  Optional<Instant> lastClosedAt() {
    return db.sql("SELECT max(closed_at) FROM crisis_alert")
        .query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
        .single();
  }

  // --- The rota ---------------------------------------------------------------------------------

  List<Shift> onCall(String role, Instant at) {
    return db.sql(
            """
            SELECT * FROM crisis_rota WHERE role = :r AND starts_at <= :at AND ends_at > :at
             ORDER BY starts_at
            """)
        .param("r", role)
        .param("at", Timestamp.from(at))
        .query(CrisisAlerts::shift)
        .list();
  }

  List<Shift> rota(Instant from) {
    return db.sql("SELECT * FROM crisis_rota WHERE ends_at > :f ORDER BY starts_at, role")
        .param("f", Timestamp.from(from))
        .query(CrisisAlerts::shift)
        .list();
  }

  /** Replaces current and future shifts; past ones stay as the record of who was on call. */
  void replaceRota(List<Shift> shifts, Instant now) {
    db.sql("DELETE FROM crisis_rota WHERE ends_at > :now AND starts_at > :now")
        .param("now", Timestamp.from(now))
        .update();
    db.sql("UPDATE crisis_rota SET ends_at = :now WHERE starts_at <= :now AND ends_at > :now")
        .param("now", Timestamp.from(now))
        .update();
    for (Shift s : shifts) {
      db.sql(
              """
              INSERT INTO crisis_rota (id, staff_id, name, phone, language, role, starts_at, ends_at)
              VALUES (:id, :staff, :name, :phone, :lang, :role, :s, :e)
              """)
          .param("id", UUID.randomUUID())
          .param("staff", s.staffId())
          .param("name", s.name())
          .param("phone", s.phone())
          .param("lang", s.language())
          .param("role", s.role())
          .param("s", Timestamp.from(s.startsAt().isBefore(now) ? now : s.startsAt()))
          .param("e", Timestamp.from(s.endsAt()))
          .update();
    }
  }

  private static Alert alert(ResultSet rs, int n) throws SQLException {
    return new Alert(
        rs.getObject("id", UUID.class),
        rs.getObject("decision_id", UUID.class),
        rs.getString("source"),
        rs.getObject("source_id", UUID.class),
        rs.getObject("subject_id", UUID.class),
        rs.getBoolean("guest"),
        rs.getString("category"),
        rs.getString("language"),
        rs.getString("status"),
        rs.getTimestamp("raised_at").toInstant(),
        instant(rs, "escalated_backup_at"),
        instant(rs, "escalated_admins_at"),
        rs.getString("acknowledged_by"),
        rs.getString("acknowledged_by_name"),
        instant(rs, "acknowledged_at"),
        rs.getString("outcome"),
        rs.getString("note"),
        instant(rs, "closed_at"));
  }

  private static Shift shift(ResultSet rs, int n) throws SQLException {
    return new Shift(
        rs.getObject("id", UUID.class),
        rs.getString("staff_id"),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("language"),
        rs.getString("role"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  @Override
  public int openFor(UUID subjectId) {
    return db.sql("SELECT count(*) FROM crisis_alert WHERE subject_id = :s AND status <> 'CLOSED'")
        .param("s", subjectId)
        .query(Integer.class)
        .single();
  }
}
