package care.solace.tracking.sync;

import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.elements.ElementLibrary.Element;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sync push and pull (Tracking Service Spec, Data model; D49). Append-only: a correction tombstones
 * and re-logs. Replaying a client_entry_id changes nothing. Days are local calendar days plus the
 * time zone they were logged in. After a push or delete, {@link EntriesChanged} lets the cycle
 * model and the pattern check run in the same transaction.
 */
@Service
public class Sync implements care.solace.tracking.PersonalData {

  public static final int PULL_LIMIT = 1000;

  /** The value of one entry, already parsed from JSON. */
  public record Value(Integer integer, String text, Boolean bool, LocalDate date) {}

  public record Entry(
      UUID clientEntryId,
      String elementCode,
      String elementVersion,
      LocalDate observedOn,
      String timeZone,
      JsonNode value,
      Instant recordedAt) {}

  public record Deletion(UUID observationId, UUID clientEntryId) {}

  public record Accepted(UUID clientEntryId, UUID observationId) {}

  public record Rejected(UUID clientEntryId, String code) {}

  public record PushResult(List<Accepted> accepted, List<Rejected> rejected, long cursor) {}

  /**
   * One observation as stored.
   *
   * @param note the note's text, for the owner only
   */
  public record Observation(
      UUID id,
      UUID userId,
      UUID clientEntryId,
      UUID elementId,
      String elementCode,
      LocalDate observedOn,
      String timeZone,
      Value value,
      String note,
      Instant recordedAt,
      Instant deletedAt,
      long changeSeq) {}

  public record PullResult(
      List<Observation> live, List<UUID> deleted, long cursor, boolean hasMore) {}

  /** Published inside the push transaction: whose data changed, and which elements. */
  public record EntriesChanged(UUID userId, Set<String> elementCodes) {}

  private final JdbcClient db;
  private final ElementLibrary library;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  Sync(JdbcClient db, ElementLibrary library, ApplicationEventPublisher events, Clock clock) {
    this.db = db;
    this.library = library;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public PushResult push(
      UUID user, String deviceId, List<Entry> entries, List<Deletion> deletions) {
    var accepted = new ArrayList<Accepted>();
    var rejected = new ArrayList<Rejected>();
    Set<String> changed = new LinkedHashSet<>();
    for (Entry e : entries) {
      var existing = byClientId(user, e.clientEntryId());
      if (existing.isPresent()) {
        accepted.add(new Accepted(e.clientEntryId(), existing.get()));
        continue;
      }
      Optional<Element> element = library.find(e.elementCode(), e.elementVersion());
      if (element.isEmpty()) {
        rejected.add(new Rejected(e.clientEntryId(), "tracking.unknown-element"));
        continue;
      }
      if (isFuture(e.observedOn(), e.timeZone())) {
        rejected.add(new Rejected(e.clientEntryId(), "tracking.future-day"));
        continue;
      }
      Optional<Value> value = parse(element.get(), e.value());
      if (value.isEmpty()) {
        rejected.add(new Rejected(e.clientEntryId(), "tracking.invalid-value"));
        continue;
      }
      accepted.add(
          new Accepted(e.clientEntryId(), insert(user, deviceId, element.get(), e, value.get())));
      changed.add(element.get().code());
    }
    for (Deletion d : deletions) {
      delete(user, d).ifPresent(changed::add);
    }
    if (!changed.isEmpty()) {
      events.publishEvent(new EntriesChanged(user, changed));
    }
    return new PushResult(accepted, rejected, cursor(user));
  }

  /** Deletes one entry and its note; returns its element code when something was deleted. */
  @Transactional
  public Optional<String> deleteOne(UUID user, UUID observationId) {
    var code = delete(user, new Deletion(observationId, null));
    code.ifPresent(c -> events.publishEvent(new EntriesChanged(user, Set.of(c))));
    return code;
  }

  public PullResult pull(UUID user, long since) {
    var rows =
        db.sql(
                """
                SELECT o.*, n.body FROM observation o LEFT JOIN entry_note n ON n.observation_id = o.id
                 WHERE o.user_id = :u AND o.change_seq > :since
                 ORDER BY o.change_seq LIMIT :n
                """)
            .param("u", user)
            .param("since", since)
            .param("n", PULL_LIMIT + 1)
            .query(Sync::row)
            .list();
    boolean more = rows.size() > PULL_LIMIT;
    if (more) {
      rows = rows.subList(0, PULL_LIMIT);
    }
    long cursor = rows.isEmpty() ? since : rows.get(rows.size() - 1).changeSeq();
    return new PullResult(
        rows.stream().filter(o -> o.deletedAt() == null).toList(),
        rows.stream().filter(o -> o.deletedAt() != null).map(Observation::id).toList(),
        cursor,
        more);
  }

  /** Live observations of some elements for a person, oldest day first (for signals). */
  public List<Observation> live(UUID user, Set<String> codes, LocalDate from) {
    if (codes.isEmpty()) {
      return List.of();
    }
    return db.sql(
            """
            SELECT o.*, NULL AS body FROM observation o
             WHERE o.user_id = :u AND o.deleted_at IS NULL AND o.element_code IN (:codes)
               AND o.observed_on >= :from
             ORDER BY o.observed_on, o.recorded_at
            """)
        .param("u", user)
        .param("codes", codes)
        .param("from", from)
        .query(Sync::row)
        .list();
  }

  /**
   * Live observations for a specialist's read of shared data (D52). Notes come only when the grant
   * includes them; otherwise the note column is never read.
   */
  public List<Observation> liveWithNotes(
      UUID user, Set<String> codes, LocalDate from, boolean withNotes) {
    if (codes.isEmpty()) {
      return List.of();
    }
    String select =
        withNotes
            ? "SELECT o.*, n.body FROM observation o"
                + " LEFT JOIN entry_note n ON n.observation_id = o.id"
            : "SELECT o.*, NULL AS body FROM observation o";
    return db.sql(
            select
                + """

                 WHERE o.user_id = :u AND o.deleted_at IS NULL AND o.element_code IN (:codes)
                   AND o.observed_on >= :from
                 ORDER BY o.observed_on, o.recorded_at
                """)
        .param("u", user)
        .param("codes", codes)
        .param("from", from)
        .query(Sync::row)
        .list();
  }

  /** Erasure: every observation and note of the person, for real (D40). */
  @Override
  public void erase(UUID user) {
    db.sql("DELETE FROM observation WHERE user_id = :u").param("u", user).update();
  }

  // --- Writing ----------------------------------------------------------------------------------

  private UUID insert(UUID user, String deviceId, Element element, Entry e, Value v) {
    UUID id = UUID.randomUUID();
    boolean dayState = element.kind() == ElementLibrary.Kind.DAY_STATE;
    boolean stale = false;
    if (dayState) {
      // A newer push supersedes; an older one arrives already tombstoned (offline phones).
      var current =
          db.sql(
                  """
                  SELECT id, recorded_at FROM observation
                   WHERE user_id = :u AND element_code = :c AND observed_on = :d
                     AND day_state AND deleted_at IS NULL FOR UPDATE
                  """)
              .param("u", user)
              .param("c", element.code())
              .param("d", e.observedOn())
              .query(
                  (rs, n) ->
                      new Object[] {
                        rs.getObject("id", UUID.class), rs.getTimestamp("recorded_at").toInstant()
                      })
              .optional();
      if (current.isPresent()) {
        Instant currentAt = (Instant) current.get()[1];
        if (currentAt.isAfter(e.recordedAt())) {
          stale = true;
        } else {
          tombstone((UUID) current.get()[0]);
        }
      }
    }
    Instant now = clock.instant();
    db.sql(
            """
            INSERT INTO observation (id, user_id, client_entry_id, element_id, element_code,
                                     observed_on, recorded_tz, value_int, value_text, value_bool,
                                     value_date, day_state, device_id, recorded_at, received_at,
                                     deleted_at, change_seq)
            VALUES (:id, :u, :c, :el, :code, :d, :tz, :vi, :vt, :vb, :vd, :ds, :dev, :at, :now,
                    :del, nextval('observation_change'))
            """)
        .param("id", id)
        .param("u", user)
        .param("c", e.clientEntryId())
        .param("el", element.id())
        .param("code", element.code())
        .param("d", e.observedOn())
        .param("tz", e.timeZone())
        .param("vi", v.integer())
        .param("vt", element.isNote() ? null : v.text())
        .param("vb", v.bool())
        .param("vd", v.date())
        .param("ds", dayState)
        .param("dev", deviceId)
        .param("at", Timestamp.from(e.recordedAt()))
        .param("now", Timestamp.from(now))
        .param("del", stale ? Timestamp.from(now) : null)
        .update();
    if (element.isNote() && !stale) {
      db.sql("INSERT INTO entry_note (observation_id, body) VALUES (:id, :b)")
          .param("id", id)
          .param("b", v.text())
          .update();
    }
    return id;
  }

  private Optional<String> delete(UUID user, Deletion d) {
    var row =
        db.sql(
                """
                SELECT id, element_code FROM observation
                 WHERE user_id = :u AND deleted_at IS NULL
                   AND (id = :id OR client_entry_id = :c) FOR UPDATE
                """)
            .param("u", user)
            .param("id", d.observationId())
            .param("c", d.clientEntryId())
            .query((rs, n) -> new String[] {rs.getString("id"), rs.getString("element_code")})
            .optional();
    row.ifPresent(r -> tombstone(UUID.fromString(r[0])));
    return row.map(r -> r[1]);
  }

  /** Marks the row deleted, with a new change number, and deletes its note for real. */
  private void tombstone(UUID id) {
    db.sql(
            """
            UPDATE observation SET deleted_at = :now, change_seq = nextval('observation_change')
             WHERE id = :id
            """)
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
    db.sql("DELETE FROM entry_note WHERE observation_id = :id").param("id", id).update();
  }

  private Optional<UUID> byClientId(UUID user, UUID clientEntryId) {
    return db.sql("SELECT id FROM observation WHERE user_id = :u AND client_entry_id = :c")
        .param("u", user)
        .param("c", clientEntryId)
        .query(UUID.class)
        .optional();
  }

  private long cursor(UUID user) {
    return db.sql("SELECT coalesce(max(change_seq), 0) FROM observation WHERE user_id = :u")
        .param("u", user)
        .query(Long.class)
        .single();
  }

  /** A day after today where it was logged is refused; time zones allow a day ahead of UTC. */
  private boolean isFuture(LocalDate day, String timeZone) {
    try {
      return day.isAfter(LocalDate.now(clock.withZone(ZoneId.of(timeZone))));
    } catch (DateTimeException e) {
      return true;
    }
  }

  /** Validates a value against its element's type and constraints (Spec: validated on push). */
  static Optional<Value> parse(Element element, JsonNode value) {
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    JsonNode c = element.constraints();
    switch (element.valueType()) {
      case INT, MINUTES -> {
        if (!value.isIntegralNumber()) {
          return Optional.empty();
        }
        int n = value.asInt();
        if ((c.has("min") && n < c.get("min").asInt())
            || (c.has("max") && n > c.get("max").asInt())) {
          return Optional.empty();
        }
        if (c.has("step") && n % c.get("step").asInt() != 0) {
          return Optional.empty();
        }
        return Optional.of(new Value(n, null, null, null));
      }
      case ENUM -> {
        if (!value.isTextual()) {
          return Optional.empty();
        }
        for (JsonNode allowed : c.path("values")) {
          if (allowed.asText().equals(value.asText())) {
            return Optional.of(new Value(null, value.asText(), null, null));
          }
        }
        return Optional.empty();
      }
      case BOOL -> {
        return value.isBoolean()
            ? Optional.of(new Value(null, null, value.asBoolean(), null))
            : Optional.empty();
      }
      case DATE -> {
        try {
          return Optional.of(new Value(null, null, null, LocalDate.parse(value.asText())));
        } catch (DateTimeException e) {
          return Optional.empty();
        }
      }
      case TEXT -> {
        String text = value.isTextual() ? value.asText().strip() : "";
        int max = c.path("max").asInt(4000);
        return text.isEmpty() || text.length() > max
            ? Optional.empty()
            : Optional.of(new Value(null, text, null, null));
      }
      default -> {
        return Optional.empty();
      }
    }
  }

  private static Observation row(ResultSet rs, int n) throws SQLException {
    Timestamp deleted = rs.getTimestamp("deleted_at");
    java.sql.Date date = rs.getDate("value_date");
    return new Observation(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("client_entry_id", UUID.class),
        rs.getObject("element_id", UUID.class),
        rs.getString("element_code"),
        rs.getDate("observed_on").toLocalDate(),
        rs.getString("recorded_tz"),
        new Value(
            (Integer) rs.getObject("value_int"),
            rs.getString("value_text"),
            (Boolean) rs.getObject("value_bool"),
            date == null ? null : date.toLocalDate()),
        rs.getString("body"),
        rs.getTimestamp("recorded_at").toInstant(),
        deleted == null ? null : deleted.toInstant(),
        rs.getLong("change_seq"));
  }
}
