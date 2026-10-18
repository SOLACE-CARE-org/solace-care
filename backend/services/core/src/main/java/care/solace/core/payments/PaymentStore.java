package care.solace.core.payments;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code payment}, {@code refund}, {@code earning} and {@code webhook_event}. */
@Repository
class PaymentStore {

  private final JdbcClient db;

  PaymentStore(JdbcClient db) {
    this.db = db;
  }

  // --- Payments ---------------------------------------------------------------------------------

  record Payment(
      UUID id,
      UUID bookingId,
      String provider,
      String method,
      String phone,
      long amount,
      BigDecimal feePercent,
      String status,
      String providerRef,
      String failureReason,
      Instant createdAt,
      Instant completedAt) {}

  void insertPayment(Payment p) {
    db.sql(
            """
            INSERT INTO payment (id, booking_id, provider, method, phone, amount, fee_percent, status,
                                 created_at, updated_at)
            VALUES (:id, :b, :pr, :m, :ph, :a, :f, 'PENDING', :now, :now)
            """)
        .param("id", p.id())
        .param("b", p.bookingId())
        .param("pr", p.provider())
        .param("m", p.method())
        .param("ph", p.phone())
        .param("a", p.amount())
        .param("f", p.feePercent())
        .param("now", Timestamp.from(p.createdAt()))
        .update();
  }

  void setProviderRef(UUID paymentId, String providerRef) {
    db.sql("UPDATE payment SET provider_ref = :r WHERE id = :id")
        .param("id", paymentId)
        .param("r", providerRef)
        .update();
  }

  Optional<Payment> payment(UUID id) {
    return db.sql("SELECT * FROM payment WHERE id = :id")
        .param("id", id)
        .query(PaymentStore::payment)
        .optional();
  }

  Optional<Payment> paymentByProviderRef(String provider, String ref) {
    return db.sql("SELECT * FROM payment WHERE provider = :p AND provider_ref = :r")
        .param("p", provider)
        .param("r", ref)
        .query(PaymentStore::payment)
        .optional();
  }

  Optional<Payment> latestPayment(UUID bookingId) {
    return db.sql("SELECT * FROM payment WHERE booking_id = :b ORDER BY created_at DESC LIMIT 1")
        .param("b", bookingId)
        .query(PaymentStore::payment)
        .optional();
  }

  /** The payment that paid for the booking, if any did. The first one wins; others are refunded. */
  Optional<Payment> completedPayment(UUID bookingId) {
    return db.sql(
            """
            SELECT * FROM payment WHERE booking_id = :b AND status = 'COMPLETE'
             ORDER BY completed_at LIMIT 1
            """)
        .param("b", bookingId)
        .query(PaymentStore::payment)
        .optional();
  }

  List<Payment> payments(String status, int limit) {
    return db.sql(
            "SELECT * FROM payment WHERE (CAST(:s AS text) IS NULL OR status = :s)"
                + " ORDER BY created_at DESC LIMIT :n")
        .param("s", status)
        .param("n", limit)
        .query(PaymentStore::payment)
        .list();
  }

  List<Payment> pendingPayments(int limit) {
    return db.sql("SELECT * FROM payment WHERE status = 'PENDING' ORDER BY created_at LIMIT :n")
        .param("n", limit)
        .query(PaymentStore::payment)
        .list();
  }

  List<Payment> paymentsBetween(Instant from, Instant to) {
    return db.sql(
            """
            SELECT * FROM payment WHERE created_at >= :f AND created_at < :t
               AND provider_ref IS NOT NULL
            """)
        .param("f", Timestamp.from(from))
        .param("t", Timestamp.from(to))
        .query(PaymentStore::payment)
        .list();
  }

  /** Earlier unfinished requests for the booking stop counting when a new one is made. */
  void replacePending(UUID bookingId, Instant now) {
    db.sql(
            """
            UPDATE payment SET status = 'FAILED', failure_reason = 'REPLACED', updated_at = :now
             WHERE booking_id = :b AND status = 'PENDING'
            """)
        .param("b", bookingId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Marks the payment complete; true only for the call that did it. Money that arrives after a
   * request failed on Solace's side (timed out, replaced) still counts.
   */
  boolean completePayment(UUID id, Instant now) {
    return db.sql(
                """
                UPDATE payment SET status = 'COMPLETE', completed_at = :now, failure_reason = NULL,
                       updated_at = :now
                 WHERE id = :id AND status <> 'COMPLETE'
                """)
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  boolean failPayment(UUID id, String reason, Instant now) {
    return db.sql(
                """
                UPDATE payment SET status = 'FAILED', failure_reason = :r, updated_at = :now
                 WHERE id = :id AND status = 'PENDING'
                """)
            .param("id", id)
            .param("r", reason)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  private static Payment payment(ResultSet rs, int n) throws SQLException {
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getString("provider"),
        rs.getString("method"),
        rs.getString("phone"),
        rs.getLong("amount"),
        rs.getBigDecimal("fee_percent"),
        rs.getString("status"),
        rs.getString("provider_ref"),
        rs.getString("failure_reason"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "completed_at"));
  }

  // --- Refunds ----------------------------------------------------------------------------------

  record Refund(
      UUID id,
      UUID bookingId,
      UUID paymentId,
      long amount,
      String reason,
      Integer policyVersion,
      String proposedBy,
      String proposedByName,
      String status,
      String decidedBy,
      String decidedByName,
      String providerRef,
      String failureReason,
      Instant createdAt) {

    boolean bySystem() {
      return proposedBy == null;
    }
  }

  void insertRefund(Refund r) {
    db.sql(
            """
            INSERT INTO refund (id, booking_id, payment_id, amount, reason, policy_version,
                                proposed_by, proposed_by_name, status, created_at, updated_at)
            VALUES (:id, :b, :p, :a, :r, :v, :by, :byName, 'PROPOSED', :now, :now)
            """)
        .param("id", r.id())
        .param("b", r.bookingId())
        .param("p", r.paymentId())
        .param("a", r.amount())
        .param("r", r.reason())
        .param("v", r.policyVersion())
        .param("by", r.proposedBy())
        .param("byName", r.proposedByName())
        .param("now", Timestamp.from(r.createdAt()))
        .update();
  }

  Optional<Refund> refund(UUID id) {
    return db.sql("SELECT * FROM refund WHERE id = :id")
        .param("id", id)
        .query(PaymentStore::refund)
        .optional();
  }

  Optional<Refund> refundByProviderRef(String ref) {
    return db.sql("SELECT * FROM refund WHERE provider_ref = :r")
        .param("r", ref)
        .query(PaymentStore::refund)
        .optional();
  }

  List<Refund> refunds(String status, int limit) {
    return db.sql(
            "SELECT * FROM refund WHERE (CAST(:s AS text) IS NULL OR status = :s)"
                + " ORDER BY created_at LIMIT :n")
        .param("s", status)
        .param("n", limit)
        .query(PaymentStore::refund)
        .list();
  }

  List<Refund> refundsForPayment(UUID paymentId) {
    return db.sql("SELECT * FROM refund WHERE payment_id = :p ORDER BY created_at")
        .param("p", paymentId)
        .query(PaymentStore::refund)
        .list();
  }

  /** Moves a refund on, from {@code from}; true only for the call that did it. */
  boolean moveRefund(UUID id, String from, String to, String by, String byName, Instant now) {
    return db.sql(
                """
                UPDATE refund SET status = :to, updated_at = :now,
                       decided_by = coalesce(:by, decided_by),
                       decided_by_name = coalesce(:byName, decided_by_name),
                       decided_at = CASE WHEN CAST(:by AS text) IS NULL THEN decided_at ELSE :now END
                 WHERE id = :id AND status = :from
                """)
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .param("by", by)
            .param("byName", byName)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void refundSent(UUID id, String providerRef, Instant now) {
    db.sql(
            """
            UPDATE refund SET status = 'SENT', provider_ref = :r, failure_reason = NULL,
                   updated_at = :now
             WHERE id = :id
            """)
        .param("id", id)
        .param("r", providerRef)
        .param("now", Timestamp.from(now))
        .update();
  }

  boolean refundFinished(UUID id, String to, String reason, Instant now) {
    return db.sql(
                """
                UPDATE refund SET status = :to, failure_reason = :reason, updated_at = :now
                 WHERE id = :id AND status = 'SENT'
                """)
            .param("id", id)
            .param("to", to)
            .param("reason", reason)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  List<Refund> sentRefunds(int limit) {
    return db.sql("SELECT * FROM refund WHERE status = 'SENT' ORDER BY updated_at LIMIT :n")
        .param("n", limit)
        .query(PaymentStore::refund)
        .list();
  }

  int count(String sql) {
    return db.sql(sql).query(Integer.class).single();
  }

  private static Refund refund(ResultSet rs, int n) throws SQLException {
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getLong("amount"),
        rs.getString("reason"),
        (Integer) rs.getObject("policy_version"),
        rs.getString("proposed_by"),
        rs.getString("proposed_by_name"),
        rs.getString("status"),
        rs.getString("decided_by"),
        rs.getString("decided_by_name"),
        rs.getString("provider_ref"),
        rs.getString("failure_reason"),
        rs.getTimestamp("created_at").toInstant());
  }

  // --- Earnings ---------------------------------------------------------------------------------

  record Earning(
      UUID id,
      UUID bookingId,
      UUID specialistId,
      String kind,
      long gross,
      long fee,
      long net,
      Instant releasedAt,
      UUID payoutItemId) {}

  void insertEarning(Earning e) {
    db.sql(
            """
            INSERT INTO earning (id, booking_id, specialist_id, kind, gross, fee, net, released_at)
            VALUES (:id, :b, :s, :k, :g, :f, :n, :at)
            """)
        .param("id", e.id())
        .param("b", e.bookingId())
        .param("s", e.specialistId())
        .param("k", e.kind())
        .param("g", e.gross())
        .param("f", e.fee())
        .param("n", e.net())
        .param("at", Timestamp.from(e.releasedAt()))
        .update();
  }

  List<Earning> recentEarnings(UUID specialistId, int limit) {
    return db.sql(
            "SELECT * FROM earning WHERE specialist_id = :s ORDER BY released_at DESC LIMIT :n")
        .param("s", specialistId)
        .param("n", limit)
        .query(PaymentStore::earning)
        .list();
  }

  int earningsSince(UUID specialistId, List<String> kinds, Instant since) {
    return db.sql(
            """
            SELECT count(*) FROM earning
             WHERE specialist_id = :s AND kind IN (:k) AND released_at >= :since
            """)
        .param("s", specialistId)
        .param("k", kinds)
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  static Earning earning(ResultSet rs, int n) throws SQLException {
    return new Earning(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getString("kind"),
        rs.getLong("gross"),
        rs.getLong("fee"),
        rs.getLong("net"),
        rs.getTimestamp("released_at").toInstant(),
        rs.getObject("payout_item_id", UUID.class));
  }

  // --- Webhooks ---------------------------------------------------------------------------------

  /** True the first time an event is seen. */
  boolean firstDelivery(String provider, String eventId, Instant now) {
    return db.sql(
                """
                INSERT INTO webhook_event (provider, event_id, received_at) VALUES (:p, :e, :now)
                ON CONFLICT DO NOTHING
                """)
            .param("p", provider)
            .param("e", eventId)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
