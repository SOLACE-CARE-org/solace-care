package care.solace.tracking.nudges;

import care.solace.coresettings.CoreSettings;
import care.solace.events.EventEnvelope;
import care.solace.events.Events;
import care.solace.tracking.PersonalData;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nudge budget (Tracking Service Spec, Ports; notification-copy.md; D52). Tracking decides and
 * Core sends: at most one tracking push a day, never SMS; an invite takes that day's push in place
 * of the logging reminder; nothing after 5 ignored days in a row; nothing while paused after the
 * safety monitor flagged the person.
 */
@Service
public class Nudges implements PersonalData {

  /** Fixed by notification-copy.md: stop after 5 ignored days. */
  static final int IGNORED_LIMIT = 5;

  public enum Kind {
    REMINDER,
    INVITE
  }

  /** Payload of tracking.nudge.requested v1. The kind only: Core holds the copy. */
  public record NudgeRequested(String kind) {}

  private final CoreSettings core;
  private final JdbcClient db;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final ZoneId defaultZone;
  private final Duration window;

  Nudges(
      CoreSettings core,
      JdbcClient db,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${solace.tracking.nudges.default-zone:Africa/Douala}") ZoneId defaultZone,
      @Value("${solace.tracking.nudges.reminder-window:PT2H}") Duration window) {
    this.core = core;
    this.db = db;
    this.events = events;
    this.clock = clock;
    this.defaultZone = defaultZone;
    this.window = window;
  }

  /**
   * Sends today's logging reminders that are due: the person's earliest reminder time across active
   * modules has passed (by at most the window), they said yes to tracking, and they have not logged
   * today.
   */
  @Transactional
  public int sendDueReminders() {
    Instant now = clock.instant();
    var due =
        db.sql(
                """
                SELECT e.user_id, min(e.reminder_time) AS at
                  FROM enrolment e JOIN tracking_consent c ON c.user_id = e.user_id AND c.granted
                 WHERE e.left_at IS NULL AND e.reminder_time IS NOT NULL
                 GROUP BY e.user_id
                """)
            .query(
                (rs, n) ->
                    new Due(
                        rs.getObject("user_id", UUID.class), rs.getObject("at", LocalTime.class)))
            .list();
    int sent = 0;
    for (Due d : due) {
      ZoneId zone = zone(d.user());
      ZonedDateTime local = now.atZone(zone);
      ZonedDateTime at = local.toLocalDate().atTime(d.at()).atZone(zone);
      if (local.isBefore(at)
          || local.isAfter(
              at.plus(core.duration("solace.tracking.nudges.reminder-window", window)))) {
        continue;
      }
      if (loggedSince(d.user(), local.toLocalDate().atStartOfDay(zone).toInstant())) {
        continue;
      }
      if (send(d.user(), Kind.REMINDER, local.toLocalDate(), now)) {
        sent++;
      }
    }
    return sent;
  }

  /** An invite takes the day's push if it is still free (Tracking Service Spec, Invite pushes). */
  @Transactional
  public boolean invite(UUID user) {
    Instant now = clock.instant();
    return send(user, Kind.INVITE, now.atZone(zone(user)).toLocalDate(), now);
  }

  /** Pauses every nudge for the person until the given time, extending an earlier pause. */
  public void pause(UUID user, Instant until) {
    db.sql(
            """
            INSERT INTO nudge_pause (user_id, paused_until) VALUES (:u, :t)
            ON CONFLICT (user_id) DO UPDATE
               SET paused_until = greatest(nudge_pause.paused_until, EXCLUDED.paused_until)
            """)
        .param("u", user)
        .param("t", Timestamp.from(until))
        .update();
  }

  private boolean send(UUID user, Kind kind, LocalDate day, Instant now) {
    if (paused(user, now) || ignoredTooOften(user)) {
      return false;
    }
    int inserted =
        db.sql(
                """
                INSERT INTO nudge (user_id, local_day, kind, sent_at) VALUES (:u, :d, :k, :now)
                ON CONFLICT (user_id, local_day) DO NOTHING
                """)
            .param("u", user)
            .param("d", day)
            .param("k", kind.name())
            .param("now", Timestamp.from(now))
            .update();
    if (inserted == 0) {
      return false;
    }
    EventEnvelope event =
        Events.envelope(
            "tracking.nudge.requested", 1, user.toString(), null, new NudgeRequested(kind.name()));
    events.publishEvent(event);
    return true;
  }

  private boolean paused(UUID user, Instant now) {
    return db.sql("SELECT paused_until > :now FROM nudge_pause WHERE user_id = :u")
        .param("u", user)
        .param("now", Timestamp.from(now))
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  /**
   * The last five nudges went out and nothing was logged since the first of them. Logging again
   * starts the count over.
   */
  private boolean ignoredTooOften(UUID user) {
    List<Instant> last =
        db.sql("SELECT sent_at FROM nudge WHERE user_id = :u ORDER BY local_day DESC LIMIT :n")
            .param("u", user)
            .param("n", ignoredLimit())
            .query((rs, n) -> rs.getTimestamp("sent_at").toInstant())
            .list();
    int limit = ignoredLimit();
    return last.size() == limit && !loggedSince(user, last.get(limit - 1));
  }

  private boolean loggedSince(UUID user, Instant since) {
    return db.sql(
            """
            SELECT EXISTS (SELECT 1 FROM observation
                            WHERE user_id = :u AND recorded_at >= :since)
            """)
        .param("u", user)
        .param("since", Timestamp.from(since))
        .query(Boolean.class)
        .single();
  }

  /** The time zone of the person's latest entry; Cameroon's until they log. */
  private ZoneId zone(UUID user) {
    return db.sql(
            """
            SELECT recorded_tz FROM observation WHERE user_id = :u
             ORDER BY recorded_at DESC LIMIT 1
            """)
        .param("u", user)
        .query(String.class)
        .optional()
        .flatMap(Nudges::parse)
        .orElse(defaultZone);
  }

  private static java.util.Optional<ZoneId> parse(String zone) {
    try {
      return java.util.Optional.of(ZoneId.of(zone));
    } catch (RuntimeException e) {
      return java.util.Optional.empty();
    }
  }

  private record Due(UUID user, LocalTime at) {}

  @Override
  public void erase(UUID userId) {
    db.sql("DELETE FROM nudge WHERE user_id = :u").param("u", userId).update();
    db.sql("DELETE FROM nudge_pause WHERE user_id = :u").param("u", userId).update();
  }

  /** Nudges stop after this many ignored days ([5]; D52, D61). */
  private int ignoredLimit() {
    return (int) core.number("solace.tracking.nudges.ignored-limit", IGNORED_LIMIT);
  }
}
