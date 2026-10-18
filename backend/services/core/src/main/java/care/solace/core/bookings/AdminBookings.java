package care.solace.core.bookings;

import care.solace.core.admin.StaffAccess;
import care.solace.core.bookings.BookingRecords.NoShowBy;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bookings for staff (admin screens 7 and 8): search, the booking's own record, and the Refund or
 * no-show dispute workflow (Admin Portal Spec; D41). Money moves only through Payments' refund
 * proposals, which a different Finance member approves (D14).
 */
@Service
class AdminBookings {

  static final int LIMIT = 200;
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{4,32}");
  private static final List<String> UNSETTLED = List.of("CONFIRMED", "IN_PROGRESS");
  private static final List<String> SETTLED = List.of("COMPLETED", "NO_SHOW");

  enum Outcome {
    SPECIALIST_NO_SHOW,
    SEEKER_NO_SHOW,
    CONNECTION_PROBLEM
  }

  private final BookingStore store;
  private final DisputeStore disputes;
  private final Bookings bookings;
  private final BookingMoney money;
  private final BookingAttendance attendance;
  private final TransactionTemplate transactions;
  private final Clock clock;

  AdminBookings(
      BookingStore store,
      DisputeStore disputes,
      Bookings bookings,
      BookingMoney money,
      BookingAttendance attendance,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.disputes = disputes;
    this.bookings = bookings;
    this.money = money;
    this.attendance = attendance;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** A search as the screen sends it; {@code status} may be DISPUTED. */
  record Query(
      String status, Instant from, Instant to, String language, UUID specialistId, String q) {}

  /** A row of the search, with what the bookings table alone does not know. */
  record Found(BookingStore.Row row, boolean disputed, String waiting) {}

  List<Found> search(Query query) {
    String prefix = prefix(query.q());
    Set<UUID> disputedOnly = null;
    String status = query.status();
    if ("DISPUTED".equals(status)) {
      disputedOnly = new HashSet<>(disputes.allOpen());
      disputedOnly.addAll(attendance.allFlagged());
      status = null;
    }
    var rows =
        store.search(
            new BookingStore.Search(
                status,
                query.from(),
                query.to(),
                query.language(),
                query.specialistId(),
                prefix,
                disputedOnly),
            LIMIT);
    var ids = rows.stream().map(BookingStore.Row::id).toList();
    Set<UUID> disputed = new HashSet<>(disputes.open(ids));
    disputed.addAll(attendance.flagged(ids));
    var waiting = attendance.waiting(ids);
    return rows.stream()
        .map(r -> new Found(r, disputed.contains(r.id()), waiting.get(r.id())))
        .toList();
  }

  /** "BK-7F9D1C2E", "7f9d1c2e", or a full id: the hex, lower case; null for no search. */
  static String prefix(String q) {
    if (q == null || q.isBlank()) {
      return null;
    }
    String hex = q.trim().toLowerCase(Locale.ROOT).replaceFirst("^bk-", "").replace("-", "");
    if (!HEX.matcher(hex).matches()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return hex;
  }

  /** "BK-" and the first eight characters of the id (D41). */
  static String reference(UUID id) {
    return "BK-" + id.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  BookingStore.Row booking(UUID id) {
    return store.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /** The dispute shown on the detail: Support's, or Core's flag when neither joined. */
  record DisputeView(
      Instant openedAt,
      String openedBy,
      String note,
      String outcome,
      String resolvedBy,
      Instant resolvedAt) {}

  Optional<DisputeView> dispute(UUID bookingId) {
    var open = disputes.latest(bookingId);
    if (open.isPresent() && open.get().open()) {
      var d = open.get();
      return Optional.of(
          new DisputeView(d.openedAt(), d.openedByName(), d.note(), null, null, null));
    }
    var flag = attendance.openFlag(bookingId);
    if (flag.isPresent()) {
      return Optional.of(new DisputeView(flag.get(), null, null, null, null, null));
    }
    return open.map(
        d ->
            new DisputeView(
                d.openedAt(),
                d.openedByName(),
                d.note(),
                d.outcome(),
                d.resolvedByName(),
                d.resolvedAt()));
  }

  boolean disputed(UUID bookingId) {
    return disputes.latest(bookingId).map(DisputeStore.Dispute::open).orElse(false)
        || attendance.openFlag(bookingId).isPresent();
  }

  void openDispute(UUID bookingId, String note, StaffAccess.Staff staff) {
    var b = booking(bookingId);
    if (b.status().equals("EXPIRED") || b.status().equals("PENDING_PAYMENT")) {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    disputes.open(bookingId, note.trim(), staff.id(), staff.name(), clock.instant());
  }

  /** What the booking's policy gives back for this outcome, within what is still refundable. */
  long policyAmount(BookingStore.Row b, Outcome outcome) {
    var policy = store.policy(b.policyVersion());
    int percent =
        switch (outcome) {
          case SPECIALIST_NO_SHOW -> policy.specialistNoShowRefundPercent();
          case SEEKER_NO_SHOW -> policy.seekerNoShowRefundPercent();
          case CONNECTION_PROBLEM -> 100;
        };
    long refundable =
        money
            .money(b.id())
            .filter(m -> m.status().equals("COMPLETE"))
            .map(m -> m.amount() - m.refunded())
            .orElse(0L);
    return Math.max(0, Math.min(refundable, b.price() * percent / 100));
  }

  /**
   * Resolves the open dispute or flag. Unsettled sessions are settled first: a no-show by the party
   * who missed it, whose policy refund Core proposes as the system (D35); a connection problem
   * completes the session. Settled sessions get a refund proposed by this staff member.
   *
   * @return the refund proposed by staff, if any
   */
  Optional<UUID> resolve(
      UUID bookingId, Outcome outcome, Long amount, String reason, StaffAccess.Staff staff) {
    var b = booking(bookingId);
    if (!disputed(bookingId)) {
      throw new SolaceException(BookingError.NOT_DISPUTED);
    }
    String why = reason == null || reason.isBlank() ? null : reason.trim();
    long policy = policyAmount(b, outcome);
    Instant now = clock.instant();
    Optional<UUID> refund = Optional.empty();
    if (UNSETTLED.contains(b.status())) {
      if (outcome != Outcome.CONNECTION_PROBLEM) {
        if (amount != null && amount != policy) {
          throw new SolaceException(BookingError.POLICY_AMOUNT_ONLY);
        }
        bookings.noShow(
            bookingId,
            outcome == Outcome.SPECIALIST_NO_SHOW ? NoShowBy.SPECIALIST : NoShowBy.SEEKER,
            now);
      } else {
        bookings.complete(bookingId, now);
        refund = propose(b, amount, policy, why, outcome, staff);
      }
    } else if (SETTLED.contains(b.status())) {
      refund = propose(b, amount, policy, why, outcome, staff);
    } else {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    Long recorded = refund.isPresent() ? (amount != null ? amount : policy) : null;
    transactions.executeWithoutResult(
        s -> {
          disputes.resolve(bookingId, outcome.name(), recorded, why, staff.id(), staff.name(), now);
          attendance.resolveFlag(bookingId, outcome.name(), now);
        });
    return refund;
  }

  private Optional<UUID> propose(
      BookingStore.Row b,
      Long amount,
      long policy,
      String reason,
      Outcome outcome,
      StaffAccess.Staff staff) {
    long refund = amount != null ? amount : policy;
    if (refund != policy && reason == null) {
      throw new SolaceException(BookingError.REASON_NEEDED);
    }
    if (refund == 0) {
      return Optional.empty();
    }
    // Finance reads the outcome as a code, or the staff member's own words.
    String text = reason != null ? reason : "DISPUTE_" + outcome.name();
    return Optional.of(money.proposeRefund(b.id(), refund, text, staff.id(), staff.name()));
  }

  /** Support cancels for the seeker: the full price comes back (D33, staff cancellation). */
  BookingStore.Row cancel(UUID bookingId) {
    return bookings.cancel(booking(bookingId), Bookings.Actor.STAFF, "OTHER");
  }

  boolean canCancel(BookingStore.Row b) {
    return List.of("PENDING_PAYMENT", "CONFIRMED").contains(b.status())
        && b.start().isAfter(clock.instant());
  }
}
