package care.solace.core.payments;

import care.solace.core.bookings.BookingCancelled;
import care.solace.core.bookings.BookingCompleted;
import care.solace.core.bookings.BookingNoShow;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.SolaceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money for one booking (System Design, Payments; D14, D32, D33, D35): captured into HELD when the
 * wallet approves, released to the specialist less Solace's fee when the session completes, and
 * refunded to the wallet that paid when a policy or a staff member proposes it and a second person
 * in Finance approves.
 */
@Service
class Payments {

  static final String POLICY_CANCELLATION = "POLICY_CANCELLATION";
  static final String POLICY_NO_SHOW = "POLICY_NO_SHOW";
  static final String PAID_AFTER_EXPIRY = "PAID_AFTER_EXPIRY";
  private static final Logger LOG = LoggerFactory.getLogger(Payments.class);

  private final PaymentStore store;
  private final Ledger ledger;
  private final PaymentProvider provider;
  private final BookingRecords bookings;
  private final SpecialistOffers specialists;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final PaymentSettings settings;
  private final Clock clock;

  Payments(
      PaymentStore store,
      Ledger ledger,
      PaymentProvider provider,
      BookingRecords bookings,
      SpecialistOffers specialists,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      PaymentSettings settings,
      Clock clock) {
    this.store = store;
    this.ledger = ledger;
    this.provider = provider;
    this.bookings = bookings;
    this.specialists = specialists;
    this.events = events;
    this.transactions = transactions;
    this.settings = settings;
    this.clock = clock;
  }

  /** A staff member deciding, as audit and the refund record name them. */
  record Decider(String id, String name) {}

  // --- Taking a payment -------------------------------------------------------------------------

  /** Asks the wallet for the booking's price; an earlier unfinished request stops counting. */
  PaymentStore.Payment start(
      BookingRecords.Info booking, PaymentProvider.Method method, String phone) {
    Instant now = clock.instant();
    if (!booking.status().equals("PENDING_PAYMENT")
        || booking.payBy() == null
        || !booking.payBy().isAfter(now)) {
      throw new SolaceException(PaymentError.NOT_PAYABLE);
    }
    var payment =
        new PaymentStore.Payment(
            UUID.randomUUID(),
            booking.id(),
            provider.name(),
            method.name(),
            phone,
            booking.price(),
            specialists.feePercent(),
            "PENDING",
            null,
            null,
            now,
            null);
    transactions.executeWithoutResult(
        s -> {
          store.replacePending(booking.id(), now);
          store.insertPayment(payment);
        });
    try {
      var started =
          provider.charge(
              new PaymentProvider.ChargeRequest(
                  payment.id(), payment.amount(), method, phone, "Solace " + booking.id()));
      store.setProviderRef(payment.id(), started.providerRef());
      if (started.status() != PaymentProvider.Status.PENDING) {
        apply(store.payment(payment.id()).orElseThrow(), started.status(), null);
      }
    } catch (PaymentProvider.Unavailable e) {
      LOG.warn("Payment provider unavailable for payment {}", payment.id(), e);
      store.failPayment(payment.id(), "PROVIDER_UNAVAILABLE", clock.instant());
      throw new SolaceException(PaymentError.PROVIDER_UNAVAILABLE);
    }
    return store.payment(payment.id()).orElseThrow();
  }

  /** The booking's latest payment, asking the provider first when {@code check} is set. */
  Optional<PaymentStore.Payment> latest(UUID bookingId, boolean check) {
    var latest = store.latestPayment(bookingId);
    if (check && latest.isPresent()) {
      check(latest.get());
      latest = store.latestPayment(bookingId);
    }
    return latest;
  }

  void check(PaymentStore.Payment p) {
    if (!p.status().equals("PENDING") || p.providerRef() == null) {
      return;
    }
    try {
      var outcome = provider.chargeStatus(p.providerRef());
      if (outcome.status() == PaymentProvider.Status.PENDING
          && p.createdAt().plus(settings.pendingTimeout()).isBefore(clock.instant())) {
        apply(p, PaymentProvider.Status.FAILED, "TIMED_OUT");
      } else {
        apply(p, outcome.status(), outcome.reason());
      }
    } catch (PaymentProvider.Unavailable e) {
      LOG.warn("Could not check payment {}", p.id(), e);
    }
  }

  /** What the provider says about a payment, from a webhook or a check. */
  void apply(PaymentStore.Payment p, PaymentProvider.Status status, String reason) {
    switch (status) {
      case COMPLETE -> completed(p);
      case FAILED -> {
        if (store.failPayment(p.id(), reason == null ? "DECLINED" : reason, clock.instant())) {
          bookings
              .find(p.bookingId())
              .filter(b -> b.status().equals("PENDING_PAYMENT"))
              .ifPresent(
                  b ->
                      events.publishEvent(
                          new PaymentNotCompleted(b.id(), b.subjectId(), b.payBy())));
        }
      }
      case PENDING -> {}
    }
  }

  private void completed(PaymentStore.Payment p) {
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (!store.completePayment(p.id(), now)) {
            return;
          }
          var booking = bookings.find(p.bookingId()).orElseThrow();
          ledger.post(
              "CAPTURE",
              Ledger.Refs.booking(p.bookingId(), p.id()),
              List.of(
                  Ledger.Line.debit(
                      Ledger.Account.PROVIDER_CLEARING, null, p.bookingId(), p.amount()),
                  Ledger.Line.credit(
                      Ledger.Account.HELD, booking.specialistId(), p.bookingId(), p.amount())),
              now);
          if (bookings.confirm(p.bookingId(), now)) {
            events.publishEvent(
                new PaymentReceived(p.bookingId(), booking.subjectId(), p.amount()));
          } else {
            // Paid after the booking expired or was cancelled, or paid twice: it all goes back.
            proposeBySystem(p, p.amount(), PAID_AFTER_EXPIRY, null, now);
          }
        });
  }

  // --- What happens to held money ---------------------------------------------------------------

  @ApplicationModuleListener
  void onCompleted(BookingCompleted completed) {
    store
        .completedPayment(completed.bookingId())
        .ifPresent(
            p ->
                transactions.executeWithoutResult(
                    s ->
                        release(
                            p,
                            completed.specialistId(),
                            held(p.bookingId()),
                            "SESSION",
                            clock.instant())));
  }

  @ApplicationModuleListener
  void onCancelled(BookingCancelled cancelled) {
    store
        .completedPayment(cancelled.bookingId())
        .ifPresent(
            p ->
                transactions.executeWithoutResult(
                    s -> {
                      Instant now = clock.instant();
                      long held = held(p.bookingId());
                      long refund = Math.min(cancelled.refundAmount(), held);
                      if (refund > 0) {
                        proposeBySystem(
                            p, refund, POLICY_CANCELLATION, cancelled.policyVersion(), now);
                      } else {
                        release(p, cancelled.specialistId(), held, "RETAINED", now);
                      }
                    }));
  }

  @ApplicationModuleListener
  void onNoShow(BookingNoShow noShow) {
    store
        .completedPayment(noShow.bookingId())
        .ifPresent(
            p ->
                transactions.executeWithoutResult(
                    s -> {
                      Instant now = clock.instant();
                      var booking = bookings.find(p.bookingId()).orElseThrow();
                      long held = held(p.bookingId());
                      long refund = Math.min(held, p.amount() * noShow.refundPercent() / 100);
                      if (refund > 0) {
                        proposeBySystem(p, refund, POLICY_NO_SHOW, noShow.policyVersion(), now);
                      } else {
                        release(p, booking.specialistId(), held, "NO_SHOW", now);
                      }
                    }));
  }

  /** Releases {@code gross} of the booking's held money to the specialist, less the fee. */
  private void release(
      PaymentStore.Payment p, UUID specialistId, long gross, String kind, Instant now) {
    if (gross <= 0) {
      return;
    }
    long fee = fee(gross, p.feePercent());
    long net = gross - fee;
    ledger.post(
        "RELEASE_" + kind,
        Ledger.Refs.booking(p.bookingId(), p.id()),
        List.of(
            Ledger.Line.debit(Ledger.Account.HELD, specialistId, p.bookingId(), gross),
            Ledger.Line.credit(Ledger.Account.SPECIALIST_PAYABLE, specialistId, p.bookingId(), net),
            Ledger.Line.credit(Ledger.Account.PLATFORM_REVENUE, null, p.bookingId(), fee)),
        now);
    store.insertEarning(
        new PaymentStore.Earning(
            UUID.randomUUID(), p.bookingId(), specialistId, kind, gross, fee, net, now, null));
  }

  static long fee(long gross, BigDecimal percent) {
    return BigDecimal.valueOf(gross)
        .multiply(percent)
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
        .longValueExact();
  }

  private long held(UUID bookingId) {
    return ledger.balance(Ledger.Account.HELD, null, bookingId);
  }

  // --- Refunds ----------------------------------------------------------------------------------

  private void proposeBySystem(
      PaymentStore.Payment p, long amount, String reason, Integer policyVersion, Instant now) {
    store.insertRefund(
        new PaymentStore.Refund(
            UUID.randomUUID(),
            p.bookingId(),
            p.id(),
            amount,
            reason,
            policyVersion,
            null,
            null,
            "PROPOSED",
            null,
            null,
            null,
            null,
            now));
  }

  /** A staff member proposes a refund for a paid booking (Refund or no-show dispute). */
  PaymentStore.Refund propose(UUID bookingId, long amount, String reason, Decider by) {
    var p =
        store
            .completedPayment(bookingId)
            .orElseThrow(() -> new SolaceException(PaymentError.NOTHING_TO_REFUND));
    var refunds = store.refundsForPayment(p.id());
    if (refunds.stream()
        .anyMatch(r -> List.of("PROPOSED", "APPROVED", "SENT").contains(r.status()))) {
      throw new SolaceException(PaymentError.REFUND_OPEN);
    }
    long refunded =
        refunds.stream()
            .filter(r -> List.of("PAID", "FAILED").contains(r.status()))
            .mapToLong(PaymentStore.Refund::amount)
            .sum();
    if (amount > p.amount() - refunded) {
      throw new SolaceException(PaymentError.REFUND_TOO_LARGE);
    }
    var refund =
        new PaymentStore.Refund(
            UUID.randomUUID(),
            bookingId,
            p.id(),
            amount,
            reason,
            null,
            by.id(),
            by.name(),
            "PROPOSED",
            null,
            null,
            null,
            null,
            clock.instant());
    store.insertRefund(refund);
    return store.refund(refund.id()).orElseThrow();
  }

  /** A second person in Finance approves: the ledger posts, then the transfer goes (D14). */
  PaymentStore.Refund approve(UUID refundId, Decider by) {
    var r = proposed(refundId, by);
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (!store.moveRefund(refundId, "PROPOSED", "APPROVED", by.id(), by.name(), now)) {
            throw new SolaceException(PaymentError.REFUND_STATE);
          }
          var p = store.payment(r.paymentId()).orElseThrow();
          var booking = bookings.find(r.bookingId()).orElseThrow();
          UUID specialistId = booking.specialistId();
          long held = held(r.bookingId());
          long fromHeld = Math.min(r.amount(), held);
          long fromReleased = r.amount() - fromHeld;
          long clawFee = fee(fromReleased, p.feePercent());
          long clawNet = fromReleased - clawFee;
          List<Ledger.Line> lines = new ArrayList<>();
          lines.add(Ledger.Line.debit(Ledger.Account.HELD, specialistId, r.bookingId(), fromHeld));
          lines.add(
              Ledger.Line.debit(
                  Ledger.Account.SPECIALIST_PAYABLE, specialistId, r.bookingId(), clawNet));
          lines.add(
              Ledger.Line.debit(Ledger.Account.PLATFORM_REVENUE, null, r.bookingId(), clawFee));
          lines.add(
              Ledger.Line.credit(Ledger.Account.REFUNDS_PAYABLE, null, r.bookingId(), r.amount()));
          ledger.post(
              "REFUND_APPROVED", new Ledger.Refs(r.bookingId(), p.id(), r.id(), null), lines, now);
          if (fromReleased > 0) {
            store.insertEarning(
                new PaymentStore.Earning(
                    UUID.randomUUID(),
                    r.bookingId(),
                    specialistId,
                    "ADJUSTMENT",
                    -fromReleased,
                    -clawFee,
                    -clawNet,
                    now,
                    null));
          }
          releaseRetained(r, p, specialistId, now);
        });
    send(store.refund(refundId).orElseThrow());
    return store.refund(refundId).orElseThrow();
  }

  /** Declined: a policy refund's money goes to the specialist as retained (D35). */
  PaymentStore.Refund decline(UUID refundId, Decider by) {
    var r = proposed(refundId, by);
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          if (!store.moveRefund(refundId, "PROPOSED", "DECLINED", by.id(), by.name(), now)) {
            throw new SolaceException(PaymentError.REFUND_STATE);
          }
          var p = store.payment(r.paymentId()).orElseThrow();
          var booking = bookings.find(r.bookingId()).orElseThrow();
          releaseRetained(r, p, booking.specialistId(), now);
        });
    return store.refund(refundId).orElseThrow();
  }

  /** A failed transfer is sent again. */
  PaymentStore.Refund retry(UUID refundId) {
    if (!store.moveRefund(refundId, "FAILED", "APPROVED", null, null, clock.instant())) {
      throw new SolaceException(PaymentError.REFUND_STATE);
    }
    send(store.refund(refundId).orElseThrow());
    return store.refund(refundId).orElseThrow();
  }

  private PaymentStore.Refund proposed(UUID refundId, Decider by) {
    var r =
        store
            .refund(refundId)
            .orElseThrow(() -> new SolaceException(care.solace.errors.CommonError.NOT_FOUND));
    if (!r.status().equals("PROPOSED")) {
      throw new SolaceException(PaymentError.REFUND_STATE);
    }
    if (by.id().equals(r.proposedBy())) {
      throw new SolaceException(PaymentError.SAME_PERSON);
    }
    return r;
  }

  /** After a policy refund is decided, whatever it left in HELD goes to the specialist. */
  private void releaseRetained(
      PaymentStore.Refund r, PaymentStore.Payment p, UUID specialistId, Instant now) {
    if (r.reason().equals(POLICY_CANCELLATION) || r.reason().equals(POLICY_NO_SHOW)) {
      release(
          p,
          specialistId,
          held(r.bookingId()),
          r.reason().equals(POLICY_NO_SHOW) ? "NO_SHOW" : "RETAINED",
          now);
    }
  }

  private void send(PaymentStore.Refund r) {
    var p = store.payment(r.paymentId()).orElseThrow();
    try {
      var started =
          provider.transfer(
              new PaymentProvider.TransferRequest(
                  r.id(),
                  r.amount(),
                  PaymentProvider.Method.valueOf(p.method()),
                  p.phone(),
                  "Solace refund",
                  "Solace refund " + r.bookingId()));
      store.refundSent(r.id(), started.providerRef(), clock.instant());
      if (started.status() != PaymentProvider.Status.PENDING) {
        transferred(store.refund(r.id()).orElseThrow(), started.status(), null);
      }
    } catch (PaymentProvider.Unavailable e) {
      LOG.warn("Refund {} could not be sent", r.id(), e);
      store.moveRefund(r.id(), "APPROVED", "FAILED", null, null, clock.instant());
    }
  }

  /** What the provider says about a refund's transfer. */
  void transferred(PaymentStore.Refund r, PaymentProvider.Status status, String reason) {
    Instant now = clock.instant();
    switch (status) {
      case COMPLETE ->
          transactions.executeWithoutResult(
              s -> {
                if (!store.refundFinished(r.id(), "PAID", null, now)) {
                  return;
                }
                ledger.post(
                    "REFUND_PAID",
                    new Ledger.Refs(r.bookingId(), r.paymentId(), r.id(), null),
                    List.of(
                        Ledger.Line.debit(
                            Ledger.Account.REFUNDS_PAYABLE, null, r.bookingId(), r.amount()),
                        Ledger.Line.credit(
                            Ledger.Account.PROVIDER_CLEARING, null, r.bookingId(), r.amount())),
                    now);
                var p = store.payment(r.paymentId()).orElseThrow();
                bookings
                    .find(r.bookingId())
                    .ifPresent(
                        b ->
                            events.publishEvent(
                                new RefundSent(b.id(), b.subjectId(), r.amount(), p.method())));
              });
      case FAILED ->
          store.refundFinished(r.id(), "FAILED", reason == null ? "FAILED" : reason, now);
      case PENDING -> {}
    }
  }

  // --- Checking with the provider ---------------------------------------------------------------

  /** Pending payments and sent refunds, in case a webhook never arrives. */
  void poll() {
    for (var p : store.pendingPayments(100)) {
      check(p);
    }
    for (var r : store.sentRefunds(100)) {
      try {
        var outcome = provider.transferStatus(r.providerRef());
        transferred(r, outcome.status(), outcome.reason());
      } catch (PaymentProvider.Unavailable e) {
        LOG.warn("Could not check refund {}", r.id(), e);
      }
    }
  }

  /** A verified webhook about a payment or a refund; false if it is about neither. */
  boolean notified(PaymentProvider.Notification n) {
    if (n.kind() == PaymentProvider.Kind.PAYMENT) {
      var p =
          Optional.ofNullable(n.providerRef())
              .flatMap(ref -> store.paymentByProviderRef(provider.name(), ref))
              .or(() -> uuid(n.reference()).flatMap(store::payment));
      p.ifPresent(payment -> apply(payment, n.status(), n.reason()));
      return p.isPresent();
    }
    var r =
        Optional.ofNullable(n.providerRef())
            .flatMap(store::refundByProviderRef)
            .or(() -> uuid(n.reference()).flatMap(store::refund));
    r.ifPresent(refund -> transferred(refund, n.status(), n.reason()));
    return r.isPresent();
  }

  static Optional<UUID> uuid(String value) {
    try {
      return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
