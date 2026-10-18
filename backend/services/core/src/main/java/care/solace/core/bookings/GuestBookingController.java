package care.solace.core.bookings;

import care.solace.core.guests.GuestAccess;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guest checkout and the guest's one booking (D34; contracts/openapi/core.yaml, tag guests). These
 * paths are public at the gateway; the X-Guest-Token header is the only credential after the code.
 */
@RestController
class GuestBookingController {

  private final Bookings bookings;
  private final BookingStore store;
  private final BookingViews views;
  private final GuestAccess guests;
  private final Clock clock;
  private final Duration tokenAfterEnd;

  GuestBookingController(
      Bookings bookings,
      BookingStore store,
      BookingViews views,
      GuestAccess guests,
      Clock clock,
      @Value("${solace.bookings.guest-token-after-end:P1D}") Duration tokenAfterEnd) {
    this.bookings = bookings;
    this.store = store;
    this.views = views;
    this.guests = guests;
    this.clock = clock;
    this.tokenAfterEnd = tokenAfterEnd;
  }

  record CodeRequest(
      @NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone,
      @NotNull @Pattern(regexp = "en|fr") String appLanguage) {}

  record GuestBookingRequest(
      @NotNull UUID holdId,
      @NotBlank String sessionLanguage,
      @NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone,
      @NotNull @Pattern(regexp = "^[0-9]{6}$") String code,
      @NotNull @Pattern(regexp = "en|fr") String appLanguage) {}

  record GuestBooking(BookingViews.Booking booking, String guestToken, Instant expiresAt) {}

  @PostMapping("/v1/guest/codes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void sendCode(@Valid @RequestBody CodeRequest body) {
    guests.sendCode(body.phone(), Locale.of(body.appLanguage()));
  }

  @PostMapping("/v1/guest/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  GuestBooking create(@Valid @RequestBody GuestBookingRequest body) {
    Locale locale = Locale.of(body.appLanguage());
    UUID contact = guests.verify(body.phone(), body.code(), locale);
    var row =
        bookings.create(
            new Bookings.Booker(null, contact, locale),
            body.holdId(),
            body.sessionLanguage(),
            "SEARCH");
    Instant expiresAt = row.end().plus(tokenAfterEnd);
    String token = guests.issueToken(contact, row.id(), expiresAt);
    return new GuestBooking(view(row, locale), token, expiresAt);
  }

  @GetMapping("/v1/guest/booking")
  BookingViews.Booking get(@RequestHeader("X-Guest-Token") String token) {
    return view(booking(token), LocaleContextHolder.getLocale());
  }

  @GetMapping("/v1/guest/booking/cancellation")
  BookingViews.Quote quote(@RequestHeader("X-Guest-Token") String token) {
    return views.of(bookings.quote(booking(token), Bookings.Actor.SEEKER, clock.instant()));
  }

  @PostMapping("/v1/guest/booking/cancellation")
  BookingViews.Booking cancel(
      @RequestHeader("X-Guest-Token") String token,
      @Valid @RequestBody BookingController.CancellationReason body) {
    var cancelled = bookings.cancel(booking(token), Bookings.Actor.SEEKER, body.reason());
    return view(cancelled, LocaleContextHolder.getLocale());
  }

  @PostMapping("/v1/guest/booking/reschedule")
  BookingViews.Booking reschedule(
      @RequestHeader("X-Guest-Token") String token,
      @Valid @RequestBody BookingController.Reschedule body) {
    return view(
        bookings.reschedule(booking(token), body.holdId()), LocaleContextHolder.getLocale());
  }

  /** The guest's view adds the masked phone (GuestJoin), never the number itself. */
  private BookingViews.Booking view(BookingStore.Row b, Locale locale) {
    var view = views.of(b, false, locale);
    return b.guestContactId() == null
        ? view
        : view.withGuestPhone(guests.maskedPhone(b.guestContactId()).orElse(null));
  }

  private BookingStore.Row booking(String token) {
    var pass = guests.require(token, clock.instant());
    return store
        .find(pass.bookingId())
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }
}
