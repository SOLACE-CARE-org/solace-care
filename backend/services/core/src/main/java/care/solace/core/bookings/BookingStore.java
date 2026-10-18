package care.solace.core.bookings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code booking} and {@code cancellation_policy}. */
@Repository
class BookingStore {

  private static final String COLUMNS =
      """
      id, seeker_account_id, guest_contact_id, specialist_id, modality, duration_min, price,
      session_language, seeker_locale, starts_at, ends_at, status, source, policy_version,
      reschedules, pay_by, cancelled_by, refund_amount
      """;

  private final JdbcClient db;

  BookingStore(JdbcClient db) {
    this.db = db;
  }

  record Row(
      UUID id,
      UUID seekerAccountId,
      UUID guestContactId,
      UUID specialistId,
      String modality,
      int durationMinutes,
      long price,
      String sessionLanguage,
      Locale seekerLocale,
      Instant start,
      Instant end,
      String status,
      String source,
      int policyVersion,
      int reschedules,
      Instant payBy,
      String cancelledBy,
      Long refundAmount) {

    BookingRecords.Info info() {
      return new BookingRecords.Info(
          id,
          seekerAccountId,
          guestContactId,
          specialistId,
          start,
          end,
          modality,
          price,
          status,
          sessionLanguage,
          seekerLocale,
          source,
          payBy);
    }
  }

  record Policy(
      int version,
      int fullRefundHours,
      int partialRefundHours,
      int partialRefundPercent,
      int seekerNoShowRefundPercent,
      int specialistNoShowRefundPercent,
      int maxReschedules,
      int rescheduleMinHours) {}

  void insert(Row b) {
    db.sql(
            """
            INSERT INTO booking (id, seeker_account_id, guest_contact_id, specialist_id, modality,
                                 duration_min, price, session_language, seeker_locale, starts_at,
                                 ends_at, status, source, policy_version, pay_by)
            VALUES (:id, :s, :g, :sp, :m, :d, :p, :l, :loc, :start, :end, 'PENDING_PAYMENT', :src,
                    :pv, :payBy)
            """)
        .param("id", b.id())
        .param("s", b.seekerAccountId())
        .param("g", b.guestContactId())
        .param("sp", b.specialistId())
        .param("m", b.modality())
        .param("d", b.durationMinutes())
        .param("p", b.price())
        .param("l", b.sessionLanguage())
        .param("loc", b.seekerLocale().getLanguage())
        .param("start", Timestamp.from(b.start()))
        .param("end", Timestamp.from(b.end()))
        .param("src", b.source())
        .param("pv", b.policyVersion())
        .param("payBy", Timestamp.from(b.payBy()))
        .update();
  }

  Optional<Row> find(UUID id) {
    return db.sql("SELECT " + COLUMNS + " FROM booking WHERE id = :id")
        .param("id", id)
        .query(BookingStore::row)
        .optional();
  }

  /**
   * A seeker's or specialist's bookings. Upcoming: not yet over and still on; past: over, or ended
   * early. Expired (never paid) bookings are left out of both.
   */
  List<Row> forPerson(String column, UUID personId, boolean upcoming, Instant now) {
    String filter =
        upcoming
            ? "ends_at > :now AND status IN ('PENDING_PAYMENT', 'CONFIRMED', 'IN_PROGRESS')"
            : "status <> 'EXPIRED' AND (ends_at <= :now OR status IN ('COMPLETED', 'CANCELLED', 'NO_SHOW'))";
    return db.sql(
            "SELECT "
                + COLUMNS
                + " FROM booking WHERE "
                + column
                + " = :id AND "
                + filter
                + " ORDER BY starts_at "
                + (upcoming ? "ASC" : "DESC")
                + " LIMIT 100")
        .param("id", personId)
        .param("now", Timestamp.from(now))
        .query(BookingStore::row)
        .list();
  }

  /** The seeker's nth session with this specialist, counting this one (SessionDetail). */
  int sessionNumber(Row b) {
    if (b.seekerAccountId() == null) {
      return 1;
    }
    return db.sql(
            """
            SELECT count(*) FROM booking
             WHERE seeker_account_id = :s AND specialist_id = :sp AND starts_at <= :start
               AND status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED')
            """)
        .param("s", b.seekerAccountId())
        .param("sp", b.specialistId())
        .param("start", Timestamp.from(b.start()))
        .query(Integer.class)
        .single();
  }

  /** Moves the status from one of {@code from}; true only for the call that did it. */
  boolean move(UUID id, List<String> from, String to, String timestampColumn, Instant now) {
    return db.sql(
                "UPDATE booking SET status = :to, updated_at = :now"
                    + (timestampColumn == null ? "" : ", " + timestampColumn + " = :now")
                    + " WHERE id = :id AND status IN (:from)")
            .param("id", id)
            .param("to", to)
            .param("from", from)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  boolean cancel(UUID id, List<String> from, String by, String reason, long refund, Instant now) {
    return db.sql(
                """
                UPDATE booking
                   SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = :by,
                       cancel_reason = :reason, refund_amount = :refund, updated_at = :now
                 WHERE id = :id AND status IN (:from)
                """)
            .param("id", id)
            .param("from", from)
            .param("by", by)
            .param("reason", reason)
            .param("refund", refund)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void reschedule(UUID id, Instant start, Instant end, Instant now) {
    db.sql(
            """
            UPDATE booking SET starts_at = :s, ends_at = :e, reschedules = reschedules + 1,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("id", id)
        .param("s", Timestamp.from(start))
        .param("e", Timestamp.from(end))
        .param("now", Timestamp.from(now))
        .update();
  }

  List<UUID> unpaidBefore(Instant now, int limit) {
    return db.sql(
            """
            SELECT id FROM booking WHERE status = 'PENDING_PAYMENT' AND pay_by < :now
             ORDER BY pay_by LIMIT :n
            """)
        .param("now", Timestamp.from(now))
        .param("n", limit)
        .query(UUID.class)
        .list();
  }

  void claim(UUID guestContactId, UUID accountId) {
    db.sql(
            """
            UPDATE booking SET seeker_account_id = :a, updated_at = now()
             WHERE guest_contact_id = :g AND seeker_account_id IS NULL
            """)
        .param("a", accountId)
        .param("g", guestContactId)
        .update();
  }

  Policy currentPolicy() {
    return db.sql("SELECT * FROM cancellation_policy ORDER BY version DESC LIMIT 1")
        .query(BookingStore::policy)
        .single();
  }

  Policy policy(int version) {
    return db.sql("SELECT * FROM cancellation_policy WHERE version = :v")
        .param("v", version)
        .query(BookingStore::policy)
        .single();
  }

  private static Policy policy(ResultSet rs, int n) throws SQLException {
    return new Policy(
        rs.getInt("version"),
        rs.getInt("full_refund_hours"),
        rs.getInt("partial_refund_hours"),
        rs.getInt("partial_refund_percent"),
        rs.getInt("seeker_no_show_refund_percent"),
        rs.getInt("specialist_no_show_refund_percent"),
        rs.getInt("max_reschedules"),
        rs.getInt("reschedule_min_hours"));
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    Timestamp payBy = rs.getTimestamp("pay_by");
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("seeker_account_id", UUID.class),
        rs.getObject("guest_contact_id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getString("modality"),
        rs.getInt("duration_min"),
        rs.getLong("price"),
        rs.getString("session_language"),
        Locale.of(rs.getString("seeker_locale")),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        rs.getString("status"),
        rs.getString("source"),
        rs.getInt("policy_version"),
        rs.getInt("reschedules"),
        payBy == null ? null : payBy.toInstant(),
        rs.getString("cancelled_by"),
        (Long) rs.getObject("refund_amount"));
  }
}
