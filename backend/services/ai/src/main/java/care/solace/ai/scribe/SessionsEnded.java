package care.solace.ai.scribe;

import care.solace.events.EventEnvelope;
import care.solace.events.IdempotentConsumer;
import care.solace.events.Topics;
import care.solace.jobs.Job;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * core.session.ended (D57): every ended session is recorded (ids only) so homework can be set for
 * it. With the scribe on, its audio waits here for the pipeline; refused during the session, the
 * audio is deleted at once. A worker drafts waiting audio one session at a time, so a restart loses
 * nothing: audio without a note is picked up again.
 */
@Component
class SessionsEnded {

  static final String CONSUMER = "ai.sessions-ended";
  static final String NOTE_TOPIC = "ai.note";

  private static final Logger LOG = LoggerFactory.getLogger(SessionsEnded.class);

  /** core.session.ended v1, AI's own view of the payload. */
  record Ended(
      UUID bookingId,
      UUID specialistId,
      boolean scribe,
      String audioKey,
      String sessionLanguage,
      Boolean guest) {}

  private final IdempotentConsumer once;
  private final JdbcClient db;
  private final Notes notes;
  private final ScribePipeline pipeline;
  private final Clock clock;

  SessionsEnded(
      IdempotentConsumer once, JdbcClient db, Notes notes, ScribePipeline pipeline, Clock clock) {
    this.once = once;
    this.db = db;
    this.notes = notes;
    this.pipeline = pipeline;
    this.clock = clock;
  }

  @Bean
  static KafkaAdmin.NewTopics noteTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(NOTE_TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }

  @KafkaListener(topics = "core.session")
  void on(EventEnvelope event) {
    if (!"core.session.ended".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    Ended ended = event.payloadAs(Ended.class);
    UUID seeker = UUID.fromString(event.subjectId());
    once.handle(CONSUMER, event, () -> record(ended, seeker));
  }

  void record(Ended e, UUID seeker) {
    db.sql(
            """
            INSERT INTO ended_session (booking_id, specialist_id, seeker_subject_id, guest,
                                       session_language, ended_at)
            VALUES (:b, :sp, :se, :g, :l, :now) ON CONFLICT (booking_id) DO NOTHING
            """)
        .param("b", e.bookingId())
        .param("sp", e.specialistId())
        .param("se", seeker)
        .param("g", Boolean.TRUE.equals(e.guest()))
        .param("l", e.sessionLanguage())
        .param("now", Timestamp.from(clock.instant()))
        .update();
    if (e.audioKey() == null) {
      return;
    }
    notes.audioReceived(e.bookingId(), e.audioKey());
    if (!e.scribe()) {
      // Refused during the session: nothing is transcribed and the audio goes now.
      notes.deleteAudio(e.bookingId());
    }
  }

  /** Every minute: draft the oldest audio still waiting for its note. */
  @Job(
      name = "ai.scribe.draft-waiting",
      description = "Transcribes and drafts notes for waiting session audio",
      every = "PT1M",
      initialDelay = "PT1M")
  void work() {
    try {
      while (draftNext()) {
        // One session at a time until none is waiting.
      }
    } catch (RuntimeException e) {
      LOG.warn("The scribe pipeline failed; the session waits for the next run", e);
    }
  }

  /** Drafts one waiting session; false when none waits. */
  boolean draftNext() {
    var waiting =
        db.sql(
                """
                SELECT a.booking_id, a.audio_key, s.specialist_id, s.seeker_subject_id,
                       s.session_language
                  FROM scribe_audio a JOIN ended_session s ON s.booking_id = a.booking_id
                 WHERE a.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM session_note n WHERE n.booking_id = a.booking_id)
                 ORDER BY a.received_at LIMIT 1
                """)
            .query(
                (rs, n) ->
                    new ScribePipeline.Session(
                        rs.getObject(1, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class),
                        rs.getString(2),
                        rs.getString(5)))
            .optional();
    if (waiting.isEmpty()) {
      return false;
    }
    pipeline.run(waiting.get());
    return true;
  }
}
