package care.solace.ai.scribe;

import care.solace.ai.privacy.Sealer;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.jobs.Job;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session notes (NotesList.dc.html, NoteReview.dc.html; D57). A draft is the specialist's to edit,
 * redraft in the other format, sign or discard. Signing deletes the transcript and the session
 * audio; a draft not signed within 7 days is deleted with both. Sections, and the transcript, are
 * sealed at rest. Only the note's specialist reaches it.
 */
@Service
public class Notes {

  private static final Logger LOG = LoggerFactory.getLogger(Notes.class);

  /** Drafts and audio go after 7 days at most (System Design, Recording). A decision. */
  static final Duration DRAFT_LIFE = Duration.ofDays(7);

  private static final int PREVIEW = 120;

  public record Section(String key, String text) implements java.io.Serializable {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Note(
      UUID id,
      UUID bookingId,
      String status,
      String format,
      List<Section> sections,
      boolean aiDrafted,
      boolean riskSignals,
      boolean canRedraft,
      Instant createdAt,
      Instant signedAt,
      Instant deleteAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(
      UUID id,
      UUID bookingId,
      String status,
      String format,
      String preview,
      Instant createdAt,
      Instant signedAt,
      Instant deleteAt) {}

  /** What the pipeline hands over to store. */
  record Draft(
      UUID bookingId,
      UUID specialistId,
      UUID seekerSubjectId,
      String format,
      List<Section> sections,
      boolean aiDrafted,
      boolean riskSignals,
      String promptVersion,
      String transcript,
      String language) {}

  record Row(
      UUID id,
      UUID bookingId,
      UUID specialistId,
      UUID seekerSubjectId,
      String status,
      String format,
      byte[] sealed,
      boolean aiDrafted,
      boolean riskSignals,
      Instant createdAt,
      Instant signedAt,
      Instant deleteAt) {}

  private final JdbcClient db;
  private final Sealer sealer;
  private final ObjectMapper json;
  private final AudioStore audio;
  private final Clock clock;

  Notes(JdbcClient db, Sealer sealer, ObjectMapper json, AudioStore audio, Clock clock) {
    this.db = db;
    this.sealer = sealer;
    this.json = json;
    this.audio = audio;
    this.clock = clock;
  }

  /** The sections a format has, in order. The assessment is always the specialist's to write. */
  static List<String> keys(String format) {
    return "DAP".equals(format) ? List.of("D", "A", "P") : List.of("S", "O", "A", "P");
  }

  @Transactional
  UUID store(Draft d) {
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    int inserted =
        db.sql(
                """
                INSERT INTO session_note (id, booking_id, specialist_id, seeker_subject_id, status,
                                          format, sealed, ai_drafted, risk_signals, prompt_version,
                                          created_at, delete_at)
                VALUES (:id, :b, :sp, :se, 'DRAFT', :f, :s, :ai, :r, :v, :now, :del)
                ON CONFLICT (booking_id) DO NOTHING
                """)
            .param("id", id)
            .param("b", d.bookingId())
            .param("sp", d.specialistId())
            .param("se", d.seekerSubjectId())
            .param("f", d.format())
            .param("s", seal(d.sections()))
            .param("ai", d.aiDrafted())
            .param("r", d.riskSignals())
            .param("v", d.promptVersion())
            .param("now", Timestamp.from(now))
            .param("del", Timestamp.from(now.plus(DRAFT_LIFE)))
            .update();
    if (inserted == 0) {
      return db.sql("SELECT id FROM session_note WHERE booking_id = :b")
          .param("b", d.bookingId())
          .query(UUID.class)
          .single();
    }
    if (d.transcript() != null) {
      db.sql(
              """
              INSERT INTO note_transcript (note_id, sealed, language, created_at)
              VALUES (:n, :s, :l, :now)
              """)
          .param("n", id)
          .param("s", sealer.seal(d.transcript()))
          .param("l", d.language())
          .param("now", Timestamp.from(now))
          .update();
    }
    return id;
  }

  public List<Summary> list(UUID specialist, String status, String query) {
    String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    List<Summary> out = new ArrayList<>();
    for (Row r :
        db.sql(
                "SELECT * FROM session_note WHERE specialist_id = :sp"
                    + (status == null ? "" : " AND status = :st")
                    + " ORDER BY created_at DESC LIMIT 200")
            .param("sp", specialist)
            .params(
                status == null
                    ? java.util.Map.<String, Object>of()
                    : java.util.Map.<String, Object>of("st", status))
            .query(Notes::row)
            .list()) {
      List<Section> sections = open(r.sealed());
      if (!q.isEmpty()
          && sections.stream().noneMatch(s -> s.text().toLowerCase(Locale.ROOT).contains(q))) {
        continue;
      }
      String first =
          sections.stream().map(Section::text).filter(t -> !t.isBlank()).findFirst().orElse("");
      String preview = first.length() > PREVIEW ? first.substring(0, PREVIEW).strip() + "…" : first;
      out.add(
          new Summary(
              r.id(),
              r.bookingId(),
              r.status(),
              r.format(),
              preview,
              r.createdAt(),
              r.signedAt(),
              r.deleteAt()));
    }
    return out;
  }

  public Optional<Note> get(UUID id, UUID specialist) {
    return find(id, specialist).map(this::view);
  }

  /** The seeker the note is about, for the audit entry. */
  Optional<UUID> subjectOf(UUID id, UUID specialist) {
    return find(id, specialist).map(Row::seekerSubjectId);
  }

  @Transactional
  public Note edit(UUID id, UUID specialist, String format, List<Section> sections) {
    Row r = draft(id, specialist);
    List<Section> clean = valid(format, sections);
    db.sql("UPDATE session_note SET format = :f, sealed = :s WHERE id = :id")
        .param("id", r.id())
        .param("f", format)
        .param("s", seal(clean))
        .update();
    return get(id, specialist).orElseThrow();
  }

  Optional<String> transcript(UUID noteId) {
    return db.sql("SELECT sealed FROM note_transcript WHERE note_id = :n")
        .param("n", noteId)
        .query((rs, n) -> sealer.open(rs.getBytes(1)))
        .optional();
  }

  Optional<String> transcriptLanguage(UUID noteId) {
    return db.sql("SELECT language FROM note_transcript WHERE note_id = :n")
        .param("n", noteId)
        .query(String.class)
        .optional();
  }

  /** Requires a draft of the caller's; for a redraft from the transcript. */
  Row draft(UUID id, UUID specialist) {
    Row r = find(id, specialist).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!r.status().equals("DRAFT")) {
      throw new SolaceException(ScribeError.NOT_A_DRAFT);
    }
    return r;
  }

  @Transactional
  void replace(UUID id, String format, List<Section> sections, String promptVersion) {
    db.sql(
            "UPDATE session_note SET format = :f, sealed = :s, ai_drafted = true,"
                + " prompt_version = :v WHERE id = :id AND status = 'DRAFT'")
        .param("id", id)
        .param("f", format)
        .param("s", seal(sections))
        .param("v", promptVersion)
        .update();
  }

  /** Signs once every section is written; the transcript and the session audio go. */
  @Transactional
  public Note sign(UUID id, UUID specialist) {
    Row r = draft(id, specialist);
    if (open(r.sealed()).stream().anyMatch(s -> s.text().isBlank())) {
      throw new SolaceException(ScribeError.INCOMPLETE);
    }
    db.sql(
            "UPDATE session_note SET status = 'SIGNED', signed_at = :now, delete_at = NULL"
                + " WHERE id = :id")
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    db.sql("DELETE FROM note_transcript WHERE note_id = :n").param("n", id).update();
    deleteAudio(r.bookingId());
    return get(id, specialist).orElseThrow();
  }

  @Transactional
  public void discard(UUID id, UUID specialist) {
    Row r = draft(id, specialist);
    db.sql("DELETE FROM session_note WHERE id = :id").param("id", r.id()).update();
    deleteAudio(r.bookingId());
  }

  /** Session audio waiting for the pipeline, recorded so it is deleted on time. */
  void audioReceived(UUID bookingId, String key) {
    db.sql(
            """
            INSERT INTO scribe_audio (booking_id, audio_key, received_at) VALUES (:b, :k, :now)
            ON CONFLICT (booking_id) DO NOTHING
            """)
        .param("b", bookingId)
        .param("k", key)
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  /** Deletes the session's audio now (signed, discarded, or refused during the session). */
  void deleteAudio(UUID bookingId) {
    db.sql("SELECT audio_key FROM scribe_audio WHERE booking_id = :b AND deleted_at IS NULL")
        .param("b", bookingId)
        .query(String.class)
        .optional()
        .ifPresent(
            key -> {
              try {
                audio.delete(key);
              } catch (RuntimeException e) {
                // The 7-day sweep and the bucket's own expiry try again.
                LOG.warn("Could not delete session audio yet");
                return;
              }
              db.sql("UPDATE scribe_audio SET deleted_at = :now WHERE booking_id = :b")
                  .param("b", bookingId)
                  .param("now", Timestamp.from(clock.instant()))
                  .update();
            });
  }

  /** Hourly: drafts past 7 days go, with their transcript and audio; so does any older audio. */
  @Job(
      name = "ai.scribe.sweep",
      description = "Deletes unsigned drafts and session audio after 7 days",
      every = "PT1H",
      initialDelay = "PT5M",
      caution = true)
  void sweep() {
    try {
      sweepNow();
    } catch (RuntimeException e) {
      LOG.warn("Sweeping old drafts failed", e);
    }
  }

  void sweepNow() {
    Instant now = clock.instant();
    for (var expired :
        db.sql(
                "SELECT id, booking_id FROM session_note WHERE status = 'DRAFT' AND delete_at < :now")
            .param("now", Timestamp.from(now))
            .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
            .list()) {
      db.sql("DELETE FROM session_note WHERE id = :id").param("id", expired[0]).update();
      deleteAudio(expired[1]);
    }
    for (UUID booking :
        db.sql(
                "SELECT booking_id FROM scribe_audio WHERE deleted_at IS NULL AND received_at < :before")
            .param("before", Timestamp.from(now.minus(DRAFT_LIFE)))
            .query(UUID.class)
            .list()) {
      deleteAudio(booking);
    }
  }

  /** Erasure of either person (D57; whether Solace keeps signed notes is open, decisions.md). */
  public void erase(UUID subject) {
    for (UUID booking :
        db.sql(
                "SELECT booking_id FROM session_note WHERE specialist_id = :s OR seeker_subject_id = :s")
            .param("s", subject)
            .query(UUID.class)
            .list()) {
      deleteAudio(booking);
    }
    db.sql("DELETE FROM session_note WHERE specialist_id = :s OR seeker_subject_id = :s")
        .param("s", subject)
        .update();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private Optional<Row> find(UUID id, UUID specialist) {
    return db.sql("SELECT * FROM session_note WHERE id = :id AND specialist_id = :sp")
        .param("id", id)
        .param("sp", specialist)
        .query(Notes::row)
        .optional();
  }

  private Note view(Row r) {
    boolean transcript =
        r.status().equals("DRAFT")
            && db.sql("SELECT count(*) FROM note_transcript WHERE note_id = :n")
                    .param("n", r.id())
                    .query(Integer.class)
                    .single()
                > 0;
    return new Note(
        r.id(),
        r.bookingId(),
        r.status(),
        r.format(),
        open(r.sealed()),
        r.aiDrafted(),
        r.riskSignals(),
        transcript,
        r.createdAt(),
        r.signedAt(),
        r.deleteAt());
  }

  static List<Section> valid(String format, List<Section> sections) {
    if (!"SOAP".equals(format) && !"DAP".equals(format)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    List<String> keys = keys(format);
    List<Section> out = new ArrayList<>();
    for (String key : keys) {
      String text =
          sections.stream()
              .filter(s -> key.equals(s.key()))
              .map(Section::text)
              .findFirst()
              .orElse("");
      if (text.length() > 4000) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      out.add(new Section(key, text.strip()));
    }
    if (sections.stream().anyMatch(s -> !keys.contains(s.key()))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return out;
  }

  private byte[] seal(List<Section> sections) {
    try {
      return sealer.seal(json.writeValueAsString(sections));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<Section> open(byte[] sealed) {
    try {
      return json.readValue(sealer.open(sealed), new TypeReference<List<Section>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    Timestamp signed = rs.getTimestamp("signed_at");
    Timestamp delete = rs.getTimestamp("delete_at");
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getObject("seeker_subject_id", UUID.class),
        rs.getString("status"),
        rs.getString("format"),
        rs.getBytes("sealed"),
        rs.getBoolean("ai_drafted"),
        rs.getBoolean("risk_signals"),
        rs.getTimestamp("created_at").toInstant(),
        signed == null ? null : signed.toInstant(),
        delete == null ? null : delete.toInstant());
  }
}
