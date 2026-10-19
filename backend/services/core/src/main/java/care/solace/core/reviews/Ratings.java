package care.solace.core.reviews;

import care.solace.core.bookings.BookingRatings;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.specialists.SpecialistRatings;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ratings and reviews (D37): one per completed booking; stars count at once, words wait for
 * moderation; specialists reply once to a published review.
 */
@Service
@EnableConfigurationProperties(ReviewSettings.class)
class Ratings implements SpecialistRatings, BookingRatings {

  private final JdbcClient db;
  private final BookingRecords bookings;
  private final ReviewSettings settings;
  private final care.solace.core.admin.PlatformSettings platform;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Ratings(
      JdbcClient db,
      BookingRecords bookings,
      ReviewSettings settings,
      care.solace.core.admin.PlatformSettings platform,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.db = db;
    this.bookings = bookings;
    this.settings = settings;
    this.platform = platform;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  record Row(
      UUID id,
      UUID bookingId,
      UUID specialistId,
      UUID subjectId,
      boolean guest,
      Instant sessionAt,
      String sessionLanguage,
      int stars,
      List<String> tags,
      String text,
      String privateNote,
      List<String> flags,
      String status,
      String safety,
      String publishedText,
      String hiddenReason,
      String reply,
      Instant createdAt,
      String aiSuggestion,
      List<String> aiReasons) {

    /** "#3907" for a member, "G-2170" for a guest: enough for staff to tell reviews apart. */
    String seekerRef() {
      String digits = String.format("%04d", Math.floorMod(subjectId.hashCode(), 10_000));
      return guest ? "G-" + digits : "#" + digits;
    }
  }

  // --- Rating -----------------------------------------------------------------------------------

  void rate(BookingRecords.Info b, int stars, List<String> tags, String text, String privateNote) {
    Instant now = clock.instant();
    if (!b.status().equals("COMPLETED")
        || b.end()
            .plus(platform.duration("solace.reviews.window", settings.window()))
            .isBefore(now)) {
      throw new SolaceException(ReviewError.NOT_RATEABLE);
    }
    String written = text == null || text.isBlank() ? null : text.strip();
    String note = privateNote == null || privateNote.isBlank() ? null : privateNote.strip();
    UUID id = UUID.randomUUID();
    boolean inserted =
        Boolean.TRUE.equals(
            transactions.execute(
                s -> {
                  int n =
                      db.sql(
                              """
                              INSERT INTO rating (id, booking_id, specialist_id, subject_id, guest,
                                                  session_at, session_language, stars, tags, text,
                                                  private_note, flags, status, created_at)
                              VALUES (:id, :b, :sp, :sub, :g, :at, :lang, :stars, :tags, :text,
                                      :note, :flags, :status, :now)
                              ON CONFLICT (booking_id) DO NOTHING
                              """)
                          .param("id", id)
                          .param("b", b.id())
                          .param("sp", b.specialistId())
                          .param("sub", b.subjectId())
                          .param("g", b.seekerAccountId() == null)
                          .param("at", Timestamp.from(b.start()))
                          .param("lang", b.sessionLanguage())
                          .param("stars", stars)
                          .param("tags", tags.toArray(String[]::new))
                          .param("text", written)
                          .param("note", note)
                          .param(
                              "flags",
                              ContactDetails.in(written)
                                  ? new String[] {"CONTACT_DETAILS"}
                                  : new String[0])
                          .param("status", written == null ? "NONE" : "PENDING")
                          .param("now", Timestamp.from(now))
                          .update();
                  if (n == 1 && written != null) {
                    events.publishEvent(new ReviewSubmitted(id));
                  }
                  return n == 1;
                }));
    if (!inserted) {
      throw new SolaceException(ReviewError.ALREADY_RATED);
    }
  }

  @Override
  public Set<UUID> rated(Collection<UUID> bookingIds) {
    if (bookingIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        db.sql("SELECT booking_id FROM rating WHERE booking_id IN (:ids)")
            .param("ids", bookingIds)
            .query(UUID.class)
            .list());
  }

  // --- Averages ---------------------------------------------------------------------------------

  @Override
  public Map<UUID, Rating> shown(Collection<UUID> specialistIds) {
    Map<UUID, Rating> shown = new HashMap<>();
    averages(specialistIds)
        .forEach(
            (id, r) -> {
              if (r.count()
                  >= platform.number(
                      "solace.reviews.min-ratings-shown", settings.minRatingsShown())) {
                shown.put(id, r);
              }
            });
    return shown;
  }

  /** Every specialist's average, however few ratings; the safety monitor's flagged ones aside. */
  Map<UUID, Rating> averages(Collection<UUID> specialistIds) {
    Map<UUID, Rating> all = new HashMap<>();
    if (specialistIds.isEmpty()) {
      return all;
    }
    db.sql(
            """
            SELECT specialist_id, round(avg(stars), 1) AS average, count(*) AS n FROM rating
             WHERE specialist_id IN (:ids) AND status <> 'FLAGGED'
             GROUP BY specialist_id
            """)
        .param("ids", specialistIds)
        .query(
            (rs, n) ->
                all.put(
                    rs.getObject("specialist_id", UUID.class),
                    new Rating(rs.getDouble("average"), rs.getInt("n"))))
        .list();
    return all;
  }

  // --- Reading ----------------------------------------------------------------------------------

  List<Row> published(UUID specialistId, int limit) {
    return db.sql(
            """
            SELECT * FROM rating WHERE specialist_id = :s AND status = 'PUBLISHED'
             ORDER BY session_at DESC LIMIT :n
            """)
        .param("s", specialistId)
        .param("n", limit)
        .query(Ratings::row)
        .list();
  }

  List<Row> queue(int limit) {
    return db.sql(
            """
            SELECT * FROM rating WHERE status = 'PENDING' AND safety <> 'FLAGGED'
             ORDER BY created_at LIMIT :n
            """)
        .param("n", limit)
        .query(Ratings::row)
        .list();
  }

  Optional<Row> find(UUID id) {
    return db.sql("SELECT * FROM rating WHERE id = :id")
        .param("id", id)
        .query(Ratings::row)
        .optional();
  }

  // --- Moderation and replies -------------------------------------------------------------------

  Row publish(UUID id, boolean maskContacts, String by, String byName) {
    var r = find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!r.status().equals("PENDING")) {
      throw new SolaceException(ReviewError.WRONG_STATE);
    }
    if (r.flags().contains("CONTACT_DETAILS") && !maskContacts) {
      throw new SolaceException(ReviewError.HAS_CONTACTS);
    }
    transactions.executeWithoutResult(
        s -> {
          moderate(
              id,
              "PUBLISHED",
              maskContacts ? ContactDetails.masked(r.text()) : r.text(),
              null,
              by,
              byName);
          events.publishEvent(new ReviewPublished(id, r.specialistId()));
        });
    return find(id).orElseThrow();
  }

  Row hide(UUID id, String reason, String by, String byName) {
    var r = find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!r.status().equals("PENDING") && !r.status().equals("PUBLISHED")) {
      throw new SolaceException(ReviewError.WRONG_STATE);
    }
    moderate(id, "HIDDEN", null, reason, by, byName);
    return find(id).orElseThrow();
  }

  private void moderate(
      UUID id, String status, String publishedText, String reason, String by, String byName) {
    int n =
        db.sql(
                """
                UPDATE rating SET status = :st, published_text = :pt, hidden_reason = :reason,
                       moderated_by = :by, moderated_by_name = :name, moderated_at = :now
                 WHERE id = :id AND status IN ('PENDING', 'PUBLISHED')
                """)
            .param("id", id)
            .param("st", status)
            .param("pt", publishedText)
            .param("reason", reason)
            .param("by", by)
            .param("name", byName)
            .param("now", Timestamp.from(clock.instant()))
            .update();
    if (n != 1) {
      throw new SolaceException(ReviewError.WRONG_STATE);
    }
  }

  Row reply(UUID specialistId, UUID id, String text) {
    if (ContactDetails.in(text)) {
      throw new SolaceException(ReviewError.REPLY_HAS_CONTACTS);
    }
    var r =
        find(id)
            .filter(x -> x.specialistId().equals(specialistId) && x.status().equals("PUBLISHED"))
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    int n =
        db.sql(
                """
                UPDATE rating SET reply = :t, replied_at = :now
                 WHERE id = :id AND reply IS NULL
                """)
            .param("id", r.id())
            .param("t", text.strip())
            .param("now", Timestamp.from(clock.instant()))
            .update();
    if (n != 1) {
      throw new SolaceException(ReviewError.WRONG_STATE);
    }
    return find(id).orElseThrow();
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    Timestamp replied = rs.getTimestamp("replied_at");
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getObject("subject_id", UUID.class),
        rs.getBoolean("guest"),
        rs.getTimestamp("session_at").toInstant(),
        rs.getString("session_language"),
        rs.getInt("stars"),
        strings(rs.getArray("tags")),
        rs.getString("text"),
        rs.getString("private_note"),
        strings(rs.getArray("flags")),
        rs.getString("status"),
        rs.getString("safety"),
        rs.getString("published_text"),
        rs.getString("hidden_reason"),
        replied == null ? null : rs.getString("reply"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("ai_suggestion"),
        strings(rs.getArray("ai_reasons")));
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
