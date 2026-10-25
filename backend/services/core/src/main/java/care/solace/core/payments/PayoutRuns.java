package care.solace.core.payments;

import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payout runs (Admin Portal Spec, Payout run; D14, D35): twice a month Core drafts a run of every
 * specialist's released balance; a Finance member submits it and another Finance member or a Super
 * admin approves it, which sends the payouts through the aggregator.
 */
@Service
class PayoutRuns {

  /** Hold reasons Core sets itself; staff holds carry their own words. */
  static final String OPEN_REFUND = "OPEN_REFUND";

  static final String LAST_PAYOUT_FAILED = "LAST_PAYOUT_FAILED";
  static final String NO_PAYOUT_DETAILS = "NO_PAYOUT_DETAILS";
  static final String NOTHING_TO_PAY = "NOTHING_TO_PAY";

  private static final Logger LOG = LoggerFactory.getLogger(PayoutRuns.class);

  private final PayoutStore store;
  private final Ledger ledger;
  private final PaymentProviders providers;
  private final SpecialistOffers specialists;
  private final TransactionTemplate transactions;
  private final org.springframework.context.ApplicationEventPublisher events;
  private final ZoneId zone;
  private final Clock clock;

  PayoutRuns(
      PayoutStore store,
      Ledger ledger,
      PaymentProviders providers,
      SpecialistOffers specialists,
      TransactionTemplate transactions,
      org.springframework.context.ApplicationEventPublisher events,
      PaymentSettings settings,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.ledger = ledger;
    this.providers = providers;
    this.specialists = specialists;
    this.transactions = transactions;
    this.zone = ZoneId.of(settings.timeZone());
    this.clock = clock;
  }

  /** The 1st to the 15th, or the 16th to the month's end (D35). */
  record Period(LocalDate start, LocalDate end) {
    static Period of(LocalDate day) {
      return day.getDayOfMonth() <= 15
          ? new Period(day.withDayOfMonth(1), day.withDayOfMonth(15))
          : new Period(day.withDayOfMonth(16), day.withDayOfMonth(day.lengthOfMonth()));
    }

    Period previous() {
      return of(start.minusDays(1));
    }
  }

  LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), zone);
  }

  /** The day the next run is drafted: the day after the current period ends. */
  LocalDate nextPayoutDay() {
    return Period.of(today()).end().plusDays(1);
  }

  /** Drafts the run for the period that just closed, once. */
  void draftDue() {
    Period closed = Period.of(today()).previous();
    if (!store.hasRun(closed.start())) {
      draft(closed);
    }
  }

  UUID draft(Period period) {
    UUID runId = UUID.randomUUID();
    Instant before = period.end().plusDays(1).atStartOfDay(zone).toInstant();
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          store.insertRun(runId, period.start(), period.end(), specialists.feePercent(), now);
          for (var due : store.due(before)) {
            var details = specialists.payoutDetails(due.specialistId());
            String hold = holdReason(due, details.orElse(null));
            store.insertItem(
                new PayoutStore.Item(
                    UUID.randomUUID(),
                    runId,
                    due.specialistId(),
                    due.sessions(),
                    due.gross(),
                    due.commission(),
                    due.net(),
                    details.map(SpecialistOffers.PayoutDetails::method).orElse(null),
                    details.map(SpecialistOffers.PayoutDetails::phone).orElse(null),
                    hold == null ? "READY" : "HELD",
                    hold,
                    null,
                    null,
                    now,
                    null),
                before);
          }
        });
    return runId;
  }

  private String holdReason(PayoutStore.Due due, SpecialistOffers.PayoutDetails details) {
    if (due.net() <= 0) {
      return NOTHING_TO_PAY;
    }
    if (details == null) {
      return NO_PAYOUT_DETAILS;
    }
    if (store.openRefund(due.specialistId())) {
      return OPEN_REFUND;
    }
    var failed = store.lastFailed(due.specialistId());
    if (failed.isPresent() && !details.savedAt().isAfter(failed.get().updatedAt())) {
      return LAST_PAYOUT_FAILED;
    }
    return null;
  }

  // --- Staff steps ------------------------------------------------------------------------------

  void hold(UUID runId, UUID itemId, String reason) {
    draftItem(runId, itemId);
    if (!store.moveItem(itemId, "READY", "HELD", reason, null, clock.instant())) {
      throw new SolaceException(PaymentError.RUN_NOT_DRAFT);
    }
  }

  void release(UUID runId, UUID itemId) {
    var item = draftItem(runId, itemId);
    if (item.net() <= 0 || item.method() == null) {
      throw new SolaceException(PaymentError.RUN_NOT_DRAFT);
    }
    if (!store.moveItem(itemId, "HELD", "READY", null, null, clock.instant())) {
      throw new SolaceException(PaymentError.RUN_NOT_DRAFT);
    }
  }

  void submit(UUID runId, Payments.Decider by) {
    run(runId);
    if (!store.submitRun(runId, by.id(), by.name(), clock.instant())) {
      throw new SolaceException(PaymentError.RUN_NOT_DRAFT);
    }
    events.publishEvent(new FinanceAlerts.RunWaiting(by.id()));
  }

  /** The second person approves: ready items are paid out, held ones wait for the next run. */
  void approve(UUID runId, Payments.Decider by) {
    var run = run(runId);
    if (!run.status().equals("SUBMITTED")) {
      throw new SolaceException(PaymentError.RUN_NOT_SUBMITTED);
    }
    if (by.id().equals(run.preparedBy())) {
      throw new SolaceException(PaymentError.SAME_PERSON);
    }
    List<PayoutStore.Item> ready =
        transactions.execute(
            s -> {
              Instant now = clock.instant();
              if (!store.approveRun(runId, by.id(), by.name(), now)) {
                throw new SolaceException(PaymentError.RUN_NOT_SUBMITTED);
              }
              var items = store.items(runId);
              for (var item : items) {
                if (item.status().equals("HELD")) {
                  store.unassign(item.id());
                } else if (item.status().equals("READY")) {
                  ledger.post(
                      "PAYOUT_SENT",
                      new Ledger.Refs(null, null, null, item.id()),
                      List.of(
                          Ledger.Line.debit(
                              Ledger.Account.SPECIALIST_PAYABLE,
                              item.specialistId(),
                              null,
                              item.net()),
                          Ledger.Line.credit(
                              Ledger.Account.PAYOUTS_IN_TRANSIT,
                              item.specialistId(),
                              null,
                              item.net())),
                      now);
                }
              }
              return items.stream().filter(i -> i.status().equals("READY")).toList();
            });
    for (var item : ready) {
      send(item);
    }
    store.finishRunIfDone(runId);
  }

  /** Through the first ACTIVE provider on the PAYOUT route for the item's method (D61). */
  private void send(PayoutStore.Item item) {
    var method = PaymentProvider.Method.valueOf(item.method());
    var provider =
        providers.route(ProviderStore.Purpose.PAYOUT, method, item.net()).stream().findFirst();
    if (provider.isEmpty()) {
      LOG.warn("No payment provider can send payout {} by {}", item.id(), method);
      store.itemSent(item.id(), null, null, clock.instant());
      transferred(
          store.item(item.id()).orElseThrow(), PaymentProvider.Status.FAILED, "NO_PROVIDER");
      return;
    }
    try {
      var started =
          provider
              .get()
              .transfer(
                  new PaymentProvider.TransferRequest(
                      item.id(),
                      item.net(),
                      method,
                      item.phone(),
                      "Solace payout",
                      "Solace payout " + item.runId()));
      store.itemSent(item.id(), provider.get().name(), started.providerRef(), clock.instant());
      if (started.status() != PaymentProvider.Status.PENDING) {
        transferred(store.item(item.id()).orElseThrow(), started.status(), null);
      }
    } catch (PaymentProvider.Unavailable e) {
      LOG.warn("Payout {} could not be sent", item.id(), e);
      store.itemSent(item.id(), provider.get().name(), null, clock.instant());
      transferred(
          store.item(item.id()).orElseThrow(),
          PaymentProvider.Status.FAILED,
          "PROVIDER_UNAVAILABLE");
    }
  }

  /** What the provider says about a payout: paid, or failed and back in the balance. */
  void transferred(PayoutStore.Item item, PaymentProvider.Status status, String reason) {
    if (status == PaymentProvider.Status.PENDING) {
      return;
    }
    transactions.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          boolean paid = status == PaymentProvider.Status.COMPLETE;
          if (!store.moveItem(
              item.id(),
              "SENT",
              paid ? "PAID" : "FAILED",
              null,
              paid ? null : (reason == null ? "FAILED" : reason),
              now)) {
            return;
          }
          var run = store.run(item.runId()).orElseThrow();
          events.publishEvent(
              new PayoutSettled(
                  item.specialistId(),
                  item.net(),
                  item.method(),
                  paid,
                  run.periodStart(),
                  run.periodEnd()));
          var refs = new Ledger.Refs(null, null, null, item.id());
          if (paid) {
            ledger.post(
                "PAYOUT_PAID",
                refs,
                List.of(
                    Ledger.Line.debit(
                        Ledger.Account.PAYOUTS_IN_TRANSIT, item.specialistId(), null, item.net()),
                    Ledger.Line.clearingCredit(item.provider(), null, item.net())),
                now);
          } else {
            ledger.post(
                "PAYOUT_FAILED",
                refs,
                List.of(
                    Ledger.Line.debit(
                        Ledger.Account.PAYOUTS_IN_TRANSIT, item.specialistId(), null, item.net()),
                    Ledger.Line.credit(
                        Ledger.Account.SPECIALIST_PAYABLE, item.specialistId(), null, item.net())),
                now);
            store.unassign(item.id());
          }
        });
    store.finishRunIfDone(item.runId());
  }

  void poll() {
    for (var item : store.sentItems(100)) {
      var provider = providers.serving(item.provider());
      if (item.providerRef() == null || provider.isEmpty()) {
        continue;
      }
      try {
        var outcome = provider.get().transferStatus(item.providerRef());
        transferred(item, outcome.status(), outcome.reason());
      } catch (PaymentProvider.Unavailable e) {
        LOG.warn("Could not check payout {}", item.id(), e);
      }
    }
  }

  /** A verified webhook from {@code provider}; a reference only counts for an item sent there. */
  boolean notified(String provider, PaymentProvider.Notification n) {
    var item =
        java.util.Optional.ofNullable(n.providerRef())
            .flatMap(ref -> store.itemByProviderRef(provider, ref))
            .or(() -> Payments.uuid(n.reference()).flatMap(store::item))
            .filter(i -> provider.equals(i.provider()));
    item.ifPresent(i -> transferred(i, n.status(), n.reason()));
    return item.isPresent();
  }

  private PayoutStore.Run run(UUID runId) {
    return store.run(runId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private PayoutStore.Item draftItem(UUID runId, UUID itemId) {
    if (!run(runId).status().equals("DRAFT")) {
      throw new SolaceException(PaymentError.RUN_NOT_DRAFT);
    }
    var item = store.item(itemId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!item.runId().equals(runId)) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    return item;
  }
}
