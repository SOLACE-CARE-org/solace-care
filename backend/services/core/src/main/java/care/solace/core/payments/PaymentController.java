package care.solace.core.payments;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.guests.GuestAccess;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying for a booking (Approve payment) and the aggregator's webhooks
 * (contracts/openapi/core.yaml, tag payments). Seekers pay for their own bookings; guests for the
 * one their token is for.
 */
@RestController
class PaymentController {

  private final Payments payments;
  private final PayoutRuns payouts;
  private final PaymentProviders providers;
  private final PaymentStore store;
  private final BookingRecords bookings;
  private final Accounts accounts;
  private final GuestAccess guests;
  private final Clock clock;

  PaymentController(
      Payments payments,
      PayoutRuns payouts,
      PaymentProviders providers,
      PaymentStore store,
      BookingRecords bookings,
      Accounts accounts,
      GuestAccess guests,
      Clock clock) {
    this.payments = payments;
    this.payouts = payouts;
    this.providers = providers;
    this.store = store;
    this.bookings = bookings;
    this.accounts = accounts;
    this.guests = guests;
    this.clock = clock;
  }

  record PaymentRequest(
      @NotNull @Pattern(regexp = "MTN_MOMO|ORANGE_MONEY") String method,
      @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Payment(
      UUID id,
      UUID bookingId,
      String method,
      long amount,
      String status,
      String phone,
      Instant createdAt,
      Instant completedAt) {

    static Payment of(PaymentStore.Payment p) {
      return new Payment(
          p.id(),
          p.bookingId(),
          p.method(),
          p.amount(),
          p.status(),
          p.phone(),
          p.createdAt(),
          p.completedAt());
    }
  }

  // --- Members ----------------------------------------------------------------------------------

  @PostMapping("/v1/bookings/{bookingId}/payments")
  @ResponseStatus(HttpStatus.CREATED)
  Payment start(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody PaymentRequest body) {
    var account = accounts.require(token);
    var booking = own(account.id(), bookingId);
    String phone =
        body.phone() != null
            ? body.phone()
            : accounts
                .phone(account.id())
                .orElseThrow(() -> new SolaceException(CommonError.REQUEST_INVALID));
    return Payment.of(
        payments.start(booking, PaymentProvider.Method.valueOf(body.method()), phone));
  }

  @GetMapping("/v1/bookings/{bookingId}/payment")
  Payment latest(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @RequestParam(defaultValue = "false") boolean check) {
    var account = accounts.require(token);
    own(account.id(), bookingId);
    return latest(bookingId, check);
  }

  // --- Guests -----------------------------------------------------------------------------------

  @PostMapping("/v1/guest/booking/payments")
  @ResponseStatus(HttpStatus.CREATED)
  Payment startAsGuest(
      @RequestHeader("X-Guest-Token") String token, @Valid @RequestBody PaymentRequest body) {
    var pass = guests.require(token, clock.instant());
    var booking =
        bookings
            .find(pass.bookingId())
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    String phone =
        body.phone() != null
            ? body.phone()
            : guests
                .phone(pass.guestContactId())
                .orElseThrow(() -> new SolaceException(CommonError.REQUEST_INVALID));
    return Payment.of(
        payments.start(booking, PaymentProvider.Method.valueOf(body.method()), phone));
  }

  @GetMapping("/v1/guest/booking/payment")
  Payment latestAsGuest(
      @RequestHeader("X-Guest-Token") String token,
      @RequestParam(defaultValue = "false") boolean check) {
    return latest(guests.require(token, clock.instant()).bookingId(), check);
  }

  // --- The aggregator ---------------------------------------------------------------------------

  /** Signature first: an unsigned or wrongly signed body changes nothing (CO-4). */
  @PostMapping("/v1/webhooks/payments/{providerName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void webhook(
      @PathVariable String providerName,
      @RequestHeader HttpHeaders headers,
      @RequestBody byte[] body) {
    // Only a provider that still serves takes webhooks, each checked with its own secret (D61).
    var provider =
        providers
            .serving(providerName)
            .filter(p -> p.verify(headers, body))
            .orElseThrow(() -> new SolaceException(PaymentError.SIGNATURE_INVALID));
    var notification = provider.parse(body);
    if (notification.isEmpty()
        || !store.firstDelivery(provider.name(), notification.get().eventId(), clock.instant())) {
      return;
    }
    if (!payments.notified(provider.name(), notification.get())) {
      payouts.notified(provider.name(), notification.get());
    }
  }

  private Payment latest(UUID bookingId, boolean check) {
    return payments
        .latest(bookingId, check)
        .map(Payment::of)
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private BookingRecords.Info own(UUID accountId, UUID bookingId) {
    return bookings
        .find(bookingId)
        .filter(b -> accountId.equals(b.seekerAccountId()))
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }
}
