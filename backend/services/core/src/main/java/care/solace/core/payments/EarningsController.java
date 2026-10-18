package care.solace.core.payments;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Earnings and payouts in the specialist app (Earnings.dc.html). */
@RestController
class EarningsController {

  private static final int ACTIVITY = 20;

  private final Ledger ledger;
  private final PaymentStore payments;
  private final PayoutStore payouts;
  private final PayoutRuns runs;
  private final SpecialistOffers specialists;
  private final BookingRecords bookings;
  private final Accounts accounts;
  private final MessageSource messages;
  private final ZoneId zone;
  private final Clock clock;

  EarningsController(
      Ledger ledger,
      PaymentStore payments,
      PayoutStore payouts,
      PayoutRuns runs,
      SpecialistOffers specialists,
      BookingRecords bookings,
      Accounts accounts,
      MessageSource messages,
      PaymentSettings settings,
      Clock clock) {
    this.ledger = ledger;
    this.payments = payments;
    this.payouts = payouts;
    this.runs = runs;
    this.specialists = specialists;
    this.bookings = bookings;
    this.accounts = accounts;
    this.messages = messages;
    this.zone = ZoneId.of(settings.timeZone());
    this.clock = clock;
  }

  record PayoutTo(String method, String phoneEnd) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Activity(
      String kind, Instant at, long amount, String seekerLabel, String method, String status) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Earnings(
      long available,
      long onHold,
      int onHoldSessions,
      int sessionsThisMonth,
      int noShowsThisMonth,
      PayoutTo payoutTo,
      LocalDate nextPayout,
      List<Activity> activity) {}

  @GetMapping("/v1/me/earnings")
  Earnings earnings(@AuthenticationPrincipal Jwt token, Locale locale) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(PaymentError.NOT_A_SPECIALIST);
    }
    UUID id = account.id();
    var held = ledger.heldBySpecialist(id);
    long heldGross = held.stream().mapToLong(Long::longValue).sum();
    Instant monthStart =
        LocalDate.now(clock.withZone(zone)).withDayOfMonth(1).atStartOfDay(zone).toInstant();

    List<Activity> activity = new ArrayList<>();
    for (var e : payments.recentEarnings(id, ACTIVITY)) {
      boolean adjustment = e.kind().equals("ADJUSTMENT");
      activity.add(
          new Activity(
              adjustment ? "ADJUSTMENT" : "SESSION",
              e.releasedAt(),
              e.net(),
              adjustment ? null : seekerLabel(e.bookingId(), locale),
              null,
              null));
    }
    for (var item : payouts.recentItems(id, ACTIVITY)) {
      activity.add(
          new Activity(
              "PAYOUT", item.updatedAt(), -item.net(), null, item.method(), item.status()));
    }
    activity.sort(Comparator.comparing(Activity::at).reversed());

    return new Earnings(
        ledger.balance(Ledger.Account.SPECIALIST_PAYABLE, id, null),
        heldGross - Payments.fee(heldGross, specialists.feePercent()),
        held.size(),
        payments.earningsSince(id, List.of("SESSION"), monthStart),
        payments.earningsSince(id, List.of("NO_SHOW"), monthStart),
        specialists
            .payoutDetails(id)
            .map(
                d ->
                    new PayoutTo(
                        d.method(), d.phone().substring(Math.max(0, d.phone().length() - 2))))
            .orElse(null),
        runs.nextPayoutDay(),
        activity.subList(0, Math.min(ACTIVITY, activity.size())));
  }

  /** The seeker's chosen name, or "Guest"; never a contact detail. */
  private String seekerLabel(UUID bookingId, Locale locale) {
    return bookings
        .find(bookingId)
        .map(BookingRecords.Info::seekerAccountId)
        .flatMap(accounts::seekerDisplayName)
        .filter(name -> !name.isBlank())
        .orElseGet(() -> messages.getMessage("booking.guest-label", null, locale));
  }
}
