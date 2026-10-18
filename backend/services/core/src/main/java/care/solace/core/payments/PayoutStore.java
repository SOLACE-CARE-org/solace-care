package care.solace.core.payments;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code payout_run} and {@code payout_item}, and the earnings each item pays. */
@Repository
class PayoutStore {

  private final JdbcClient db;

  PayoutStore(JdbcClient db) {
    this.db = db;
  }

  record Run(
      UUID id,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      BigDecimal feePercent,
      String preparedBy,
      String preparedByName,
      Instant preparedAt,
      String approvedBy,
      String approvedByName,
      Instant approvedAt) {}

  record Item(
      UUID id,
      UUID runId,
      UUID specialistId,
      int sessions,
      long gross,
      long commission,
      long net,
      String method,
      String phone,
      String status,
      String holdReason,
      String providerRef,
      String failureReason,
      Instant updatedAt) {}

  /** A specialist's unpaid earnings released before {@code before}, summed. */
  record Due(UUID specialistId, int sessions, long gross, long commission, long net) {}

  boolean hasRun(LocalDate periodStart) {
    return db.sql("SELECT count(*) FROM payout_run WHERE period_start = :p")
            .param("p", periodStart)
            .query(Integer.class)
            .single()
        > 0;
  }

  void insertRun(UUID id, LocalDate start, LocalDate end, BigDecimal feePercent, Instant now) {
    db.sql(
            """
            INSERT INTO payout_run (id, period_start, period_end, status, fee_percent, created_at)
            VALUES (:id, :s, :e, 'DRAFT', :f, :now)
            """)
        .param("id", id)
        .param("s", start)
        .param("e", end)
        .param("f", feePercent)
        .param("now", Timestamp.from(now))
        .update();
  }

  List<Due> due(Instant before) {
    return db.sql(
            """
            SELECT specialist_id,
                   count(*) FILTER (WHERE kind IN ('SESSION', 'RETAINED', 'NO_SHOW')) AS sessions,
                   sum(gross) AS gross, sum(fee) AS fee, sum(net) AS net
              FROM earning
             WHERE payout_item_id IS NULL AND released_at < :before
             GROUP BY specialist_id
            """)
        .param("before", Timestamp.from(before))
        .query(
            (rs, n) ->
                new Due(
                    rs.getObject("specialist_id", UUID.class),
                    rs.getInt("sessions"),
                    rs.getLong("gross"),
                    rs.getLong("fee"),
                    rs.getLong("net")))
        .list();
  }

  void insertItem(Item i, Instant before) {
    db.sql(
            """
            INSERT INTO payout_item (id, run_id, specialist_id, sessions, gross, commission, net,
                                     method, phone, status, hold_reason, updated_at)
            VALUES (:id, :r, :s, :n, :g, :c, :net, :m, :p, :st, :h, :now)
            """)
        .param("id", i.id())
        .param("r", i.runId())
        .param("s", i.specialistId())
        .param("n", i.sessions())
        .param("g", i.gross())
        .param("c", i.commission())
        .param("net", i.net())
        .param("m", i.method())
        .param("p", i.phone())
        .param("st", i.status())
        .param("h", i.holdReason())
        .param("now", Timestamp.from(i.updatedAt()))
        .update();
    db.sql(
            """
            UPDATE earning SET payout_item_id = :i
             WHERE specialist_id = :s AND payout_item_id IS NULL AND released_at < :before
            """)
        .param("i", i.id())
        .param("s", i.specialistId())
        .param("before", Timestamp.from(before))
        .update();
  }

  /** Held items leave their earnings for the next run. */
  void unassign(UUID itemId) {
    db.sql("UPDATE earning SET payout_item_id = NULL WHERE payout_item_id = :i")
        .param("i", itemId)
        .update();
  }

  /** True when one of the specialist's bookings has a refund still being decided or sent. */
  boolean openRefund(UUID specialistId) {
    return db.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM refund r
               WHERE r.status IN ('PROPOSED', 'APPROVED')
                 AND EXISTS (SELECT 1 FROM ledger_line l
                              WHERE l.booking_id = r.booking_id AND l.specialist_id = :s))
            """)
        .param("s", specialistId)
        .query(Boolean.class)
        .single();
  }

  Optional<Item> lastFailed(UUID specialistId) {
    return db.sql(
            """
            SELECT * FROM payout_item WHERE specialist_id = :s AND status IN ('PAID', 'FAILED')
             ORDER BY updated_at DESC LIMIT 1
            """)
        .param("s", specialistId)
        .query(PayoutStore::item)
        .optional()
        .filter(i -> i.status().equals("FAILED"));
  }

  Optional<Run> run(UUID id) {
    return db.sql("SELECT * FROM payout_run WHERE id = :id")
        .param("id", id)
        .query(PayoutStore::run)
        .optional();
  }

  List<Run> runs(int limit) {
    return db.sql("SELECT * FROM payout_run ORDER BY period_start DESC LIMIT :n")
        .param("n", limit)
        .query(PayoutStore::run)
        .list();
  }

  List<Item> items(UUID runId) {
    return db.sql("SELECT * FROM payout_item WHERE run_id = :r ORDER BY net DESC, id")
        .param("r", runId)
        .query(PayoutStore::item)
        .list();
  }

  Optional<Item> item(UUID id) {
    return db.sql("SELECT * FROM payout_item WHERE id = :id")
        .param("id", id)
        .query(PayoutStore::item)
        .optional();
  }

  Optional<Item> itemByProviderRef(String ref) {
    return db.sql("SELECT * FROM payout_item WHERE provider_ref = :r")
        .param("r", ref)
        .query(PayoutStore::item)
        .optional();
  }

  List<Item> sentItems(int limit) {
    return db.sql("SELECT * FROM payout_item WHERE status = 'SENT' ORDER BY updated_at LIMIT :n")
        .param("n", limit)
        .query(PayoutStore::item)
        .list();
  }

  List<Item> recentItems(UUID specialistId, int limit) {
    return db.sql(
            """
            SELECT * FROM payout_item WHERE specialist_id = :s AND status IN ('SENT', 'PAID', 'FAILED')
             ORDER BY updated_at DESC LIMIT :n
            """)
        .param("s", specialistId)
        .param("n", limit)
        .query(PayoutStore::item)
        .list();
  }

  boolean moveRun(UUID id, String from, String to) {
    return db.sql("UPDATE payout_run SET status = :to WHERE id = :id AND status = :from")
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .update()
        == 1;
  }

  boolean submitRun(UUID id, String by, String byName, Instant now) {
    return db.sql(
                """
                UPDATE payout_run SET status = 'SUBMITTED', prepared_by = :by,
                       prepared_by_name = :name, prepared_at = :now
                 WHERE id = :id AND status = 'DRAFT'
                """)
            .param("id", id)
            .param("by", by)
            .param("name", byName)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  boolean approveRun(UUID id, String by, String byName, Instant now) {
    return db.sql(
                """
                UPDATE payout_run SET status = 'SENT', approved_by = :by, approved_by_name = :name,
                       approved_at = :now
                 WHERE id = :id AND status = 'SUBMITTED' AND prepared_by <> :by
                """)
            .param("id", id)
            .param("by", by)
            .param("name", byName)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  boolean moveItem(
      UUID id, String from, String to, String holdReason, String failureReason, Instant now) {
    return db.sql(
                """
                UPDATE payout_item SET status = :to, hold_reason = :h, failure_reason = :f,
                       updated_at = :now
                 WHERE id = :id AND status = :from
                """)
            .param("id", id)
            .param("from", from)
            .param("to", to)
            .param("h", holdReason)
            .param("f", failureReason)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  void itemSent(UUID id, String providerRef, Instant now) {
    db.sql(
            """
            UPDATE payout_item SET status = 'SENT', provider_ref = :r, updated_at = :now
             WHERE id = :id
            """)
        .param("id", id)
        .param("r", providerRef)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** A sent run is done when none of its items is still on its way. */
  void finishRunIfDone(UUID runId) {
    db.sql(
            """
            UPDATE payout_run SET status = 'DONE'
             WHERE id = :r AND status = 'SENT'
               AND NOT EXISTS (SELECT 1 FROM payout_item WHERE run_id = :r AND status = 'SENT')
            """)
        .param("r", runId)
        .update();
  }

  private static Run run(ResultSet rs, int n) throws SQLException {
    return new Run(
        rs.getObject("id", UUID.class),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getString("status"),
        rs.getBigDecimal("fee_percent"),
        rs.getString("prepared_by"),
        rs.getString("prepared_by_name"),
        PaymentStore.instant(rs, "prepared_at"),
        rs.getString("approved_by"),
        rs.getString("approved_by_name"),
        PaymentStore.instant(rs, "approved_at"));
  }

  private static Item item(ResultSet rs, int n) throws SQLException {
    return new Item(
        rs.getObject("id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getObject("specialist_id", UUID.class),
        rs.getInt("sessions"),
        rs.getLong("gross"),
        rs.getLong("commission"),
        rs.getLong("net"),
        rs.getString("method"),
        rs.getString("phone"),
        rs.getString("status"),
        rs.getString("hold_reason"),
        rs.getString("provider_ref"),
        rs.getString("failure_reason"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
