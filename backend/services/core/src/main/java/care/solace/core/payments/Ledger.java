package care.solace.core.payments;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The double-entry ledger (System Design, Payments). Every journal balances, here and again in the
 * database at commit; lines are only ever added.
 */
@Repository
class Ledger {

  enum Account {
    PROVIDER_CLEARING,
    HELD,
    SPECIALIST_PAYABLE,
    PLATFORM_REVENUE,
    REFUNDS_PAYABLE,
    PAYOUTS_IN_TRANSIT
  }

  /** {@code provider} names whose float a PROVIDER_CLEARING line is in (D61); null elsewhere. */
  record Line(
      Account account,
      UUID specialistId,
      UUID bookingId,
      long debit,
      long credit,
      String provider) {
    static Line debit(Account account, UUID specialistId, UUID bookingId, long amount) {
      return new Line(account, specialistId, bookingId, amount, 0, null);
    }

    static Line credit(Account account, UUID specialistId, UUID bookingId, long amount) {
      return new Line(account, specialistId, bookingId, 0, amount, null);
    }

    /** The money at the provider with {@code code}. */
    static Line clearingDebit(String code, UUID bookingId, long amount) {
      return new Line(Account.PROVIDER_CLEARING, null, bookingId, amount, 0, code);
    }

    static Line clearingCredit(String code, UUID bookingId, long amount) {
      return new Line(Account.PROVIDER_CLEARING, null, bookingId, 0, amount, code);
    }
  }

  /** What a journal is about; any of the ids may be null. */
  record Refs(UUID bookingId, UUID paymentId, UUID refundId, UUID payoutItemId) {
    static Refs booking(UUID bookingId, UUID paymentId) {
      return new Refs(bookingId, paymentId, null, null);
    }
  }

  private final JdbcClient db;

  Ledger(JdbcClient db) {
    this.db = db;
  }

  /** Posts one balanced journal; lines of zero are left out. Call inside a transaction. */
  UUID post(String kind, Refs refs, List<Line> lines, Instant now) {
    List<Line> kept = new ArrayList<>();
    long debits = 0;
    long credits = 0;
    for (Line l : lines) {
      if (l.debit() < 0 || l.credit() < 0) {
        throw new IllegalArgumentException("negative ledger amount in " + kind);
      }
      if (l.debit() == 0 && l.credit() == 0) {
        continue;
      }
      if ((l.account() == Account.PROVIDER_CLEARING) != (l.provider() != null)) {
        throw new IllegalArgumentException("clearing lines, and only they, name a provider");
      }
      kept.add(l);
      debits += l.debit();
      credits += l.credit();
    }
    if (debits != credits) {
      throw new IllegalStateException(
          "unbalanced journal " + kind + ": " + debits + " / " + credits);
    }
    UUID id = UUID.randomUUID();
    if (kept.isEmpty()) {
      return id;
    }
    db.sql(
            """
            INSERT INTO ledger_journal (id, kind, booking_id, payment_id, refund_id, payout_item_id,
                                        created_at)
            VALUES (:id, :k, :b, :p, :r, :o, :now)
            """)
        .param("id", id)
        .param("k", kind)
        .param("b", refs.bookingId())
        .param("p", refs.paymentId())
        .param("r", refs.refundId())
        .param("o", refs.payoutItemId())
        .param("now", Timestamp.from(now))
        .update();
    for (Line l : kept) {
      db.sql(
              """
              INSERT INTO ledger_line (journal_id, account, specialist_id, booking_id, debit, credit,
                                       provider)
              VALUES (:j, :a, :s, :b, :d, :c, :p)
              """)
          .param("j", id)
          .param("a", l.account().name())
          .param("s", l.specialistId())
          .param("b", l.bookingId())
          .param("d", l.debit())
          .param("c", l.credit())
          .param("p", l.provider())
          .update();
    }
    return id;
  }

  /**
   * A liability's balance (credits less debits), optionally for one specialist or one booking. For
   * the clearing asset this is negative while money sits at the provider.
   */
  long balance(Account account, UUID specialistId, UUID bookingId) {
    return db.sql(
            """
            SELECT coalesce(sum(credit) - sum(debit), 0) FROM ledger_line
             WHERE account = :a
               AND (CAST(:s AS uuid) IS NULL OR specialist_id = :s)
               AND (CAST(:b AS uuid) IS NULL OR booking_id = :b)
            """)
        .param("a", account.name())
        .param("s", specialistId)
        .param("b", bookingId)
        .query(Long.class)
        .single();
  }

  /** Bookings of a specialist that still have money held, and how much. */
  List<Long> heldBySpecialist(UUID specialistId) {
    return db.sql(
            """
            SELECT sum(credit) - sum(debit) FROM ledger_line
             WHERE account = 'HELD' AND specialist_id = :s
             GROUP BY booking_id HAVING sum(credit) - sum(debit) > 0
            """)
        .param("s", specialistId)
        .query(Long.class)
        .list();
  }
}
