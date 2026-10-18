package care.solace.core.payments;

import care.solace.core.bookings.BookingMoney;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A booking's money for the admin booking detail, and refunds proposed in a dispute (D41). */
@Component
class PaymentsForBookings implements BookingMoney {

  /** Refunds that count against what can still be refunded. */
  private static final List<String> COUNTED = List.of("PROPOSED", "APPROVED", "SENT", "PAID");

  private final PaymentStore store;
  private final Ledger ledger;
  private final Payments payments;

  PaymentsForBookings(PaymentStore store, Ledger ledger, Payments payments) {
    this.store = store;
    this.ledger = ledger;
    this.payments = payments;
  }

  @Override
  public Optional<Money> money(UUID bookingId) {
    return store
        .latestPayment(bookingId)
        .map(
            p -> {
              var refunds =
                  store.refundsForPayment(p.id()).stream()
                      .map(r -> new Refund(r.id(), r.amount(), r.status(), r.createdAt()))
                      .toList();
              long refunded =
                  refunds.stream()
                      .filter(r -> COUNTED.contains(r.status()))
                      .mapToLong(Refund::amount)
                      .sum();
              return new Money(
                  p.method(),
                  AdminPaymentsController.mask(p.phone()),
                  p.amount(),
                  p.status(),
                  ledger.balance(Ledger.Account.HELD, null, bookingId),
                  refunded,
                  refunds);
            });
  }

  @Override
  public UUID proposeRefund(
      UUID bookingId, long amount, String reason, String staffId, String staffName) {
    return payments
        .propose(bookingId, amount, reason, new Payments.Decider(staffId, staffName))
        .id();
  }
}
