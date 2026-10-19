package care.solace.core.sessions;

import care.solace.core.admin.FeatureFlags;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.privacy.ConsentCheck;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import care.solace.events.Topics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The session scribe's consent for one session (System Design, Recording; D57). Audio is recorded
 * only when the scribe is switched on, the seeker is registered, both people consented to AI_SCRIBE
 * and neither refused for this session. Core starts an audio-only egress once both have joined and
 * stops it when either refuses or the room ends; the AI service deletes the audio.
 */
@Service
class Scribe {

  static final String TOPIC = "core.session";

  record State(
      boolean available,
      boolean seekerConsented,
      boolean specialistConsented,
      boolean refusedForSession,
      boolean recording) {}

  /** core.session.ended v1: ids and codes only. */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record Ended(
      UUID bookingId, UUID specialistId, boolean scribe, String audioKey, String sessionLanguage) {}

  private record Row(
      boolean seekerRefused,
      boolean specialistRefused,
      String egressId,
      String audioKey,
      boolean recording) {}

  private final JdbcClient db;
  private final LiveKit liveKit;
  private final ConsentCheck consents;
  private final FeatureFlags flags;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  Scribe(
      JdbcClient db,
      LiveKit liveKit,
      ConsentCheck consents,
      FeatureFlags flags,
      ApplicationEventPublisher events,
      Clock clock) {
    this.db = db;
    this.liveKit = liveKit;
    this.consents = consents;
    this.flags = flags;
    this.events = events;
    this.clock = clock;
  }

  @Bean
  static KafkaAdmin.NewTopics sessionTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }

  State state(BookingRecords.Info b) {
    Row row = row(b.id());
    boolean seeker =
        b.seekerAccountId() != null && consents.granted(b.seekerAccountId(), "AI_SCRIBE");
    boolean specialist = consents.granted(b.specialistId(), "AI_SCRIBE");
    return new State(
        flags.enabled("sessionScribe") && b.seekerAccountId() != null,
        seeker,
        specialist,
        row.seekerRefused() || row.specialistRefused(),
        row.recording());
  }

  /** The caller allows or refuses the scribe for this session; refusing stops a recording. */
  @Transactional
  State choose(BookingRecords.Info b, Sessions.Role role, boolean on) {
    if (!b.status().equals("CONFIRMED") && !b.status().equals("IN_PROGRESS")) {
      throw new SolaceException(SessionError.NOT_JOINABLE);
    }
    String column = role == Sessions.Role.SEEKER ? "seeker_refused" : "specialist_refused";
    db.sql(
            "INSERT INTO session_scribe (booking_id, "
                + column
                + ") VALUES (:b, :r) ON CONFLICT (booking_id) DO UPDATE SET "
                + column
                + " = :r")
        .param("b", b.id())
        .param("r", !on)
        .update();
    if (!on) {
      stop(b.id());
    } else if (b.status().equals("IN_PROGRESS")) {
      start(b);
    }
    return state(b);
  }

  /** Both people are in the room: record when everything allows it. */
  void bothJoined(BookingRecords.Info b) {
    start(b);
  }

  /** The room ended: stop any recording and tell the AI service, which drafts or deletes. */
  @Transactional
  void ended(BookingRecords.Info b) {
    Row before = row(b.id());
    boolean first =
        db.sql(
                    """
                INSERT INTO session_scribe (booking_id, ended) VALUES (:b, true)
                ON CONFLICT (booking_id) DO UPDATE SET ended = true
                 WHERE session_scribe.ended = false
                """)
                .param("b", b.id())
                .update()
            == 1;
    if (!first) {
      return;
    }
    stop(b.id());
    Row row = row(b.id());
    boolean scribe = before.audioKey() != null && !row.seekerRefused() && !row.specialistRefused();
    UUID subject = b.seekerAccountId() != null ? b.seekerAccountId() : b.guestContactId();
    events.publishEvent(
        Events.envelope(
            "core.session.ended",
            1,
            subject.toString(),
            b.seekerLocale().getLanguage(),
            new Ended(b.id(), b.specialistId(), scribe, row.audioKey(), b.sessionLanguage())));
  }

  private void start(BookingRecords.Info b) {
    State s = state(b);
    if (!s.available()
        || !s.seekerConsented()
        || !s.specialistConsented()
        || s.refusedForSession()
        || s.recording()) {
      return;
    }
    String key = "scribe-audio/" + b.id() + ".ogg";
    Instant now = clock.instant();
    String egress = liveKit.startAudioEgress(b.id().toString(), key, now);
    if (egress == null) {
      return;
    }
    db.sql(
            """
            INSERT INTO session_scribe (booking_id, egress_id, audio_key, recording, started_at)
            VALUES (:b, :e, :k, true, :now)
            ON CONFLICT (booking_id) DO UPDATE
               SET egress_id = :e, audio_key = :k, recording = true, started_at = :now
            """)
        .param("b", b.id())
        .param("e", egress)
        .param("k", key)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void stop(UUID bookingId) {
    Row row = row(bookingId);
    if (!row.recording()) {
      return;
    }
    Instant now = clock.instant();
    liveKit.stopEgress(row.egressId(), now);
    db.sql("UPDATE session_scribe SET recording = false, stopped_at = :now WHERE booking_id = :b")
        .param("b", bookingId)
        .param("now", Timestamp.from(now))
        .update();
  }

  private Row row(UUID bookingId) {
    return db.sql(
            """
            SELECT seeker_refused, specialist_refused, egress_id, audio_key, recording
              FROM session_scribe WHERE booking_id = :b
            """)
        .param("b", bookingId)
        .query(
            (rs, n) ->
                new Row(
                    rs.getBoolean(1),
                    rs.getBoolean(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5)))
        .optional()
        .orElse(new Row(false, false, null, null, false));
  }
}
