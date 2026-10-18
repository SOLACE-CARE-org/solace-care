package care.solace.core.bookings;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bookings for signed-in members (contracts/openapi/core.yaml, tag bookings). */
@RestController
class BookingController {

  private final Bookings bookings;
  private final BookingStore store;
  private final BookingViews views;
  private final Accounts accounts;
  private final Clock clock;

  private final care.solace.core.invites.TrackingInvites invites;

  BookingController(
      Bookings bookings,
      BookingStore store,
      BookingViews views,
      Accounts accounts,
      Clock clock,
      care.solace.core.invites.TrackingInvites invites) {
    this.invites = invites;
    this.bookings = bookings;
    this.store = store;
    this.views = views;
    this.accounts = accounts;
    this.clock = clock;
  }

  record BookingRequest(
      @NotNull UUID holdId,
      @NotBlank String sessionLanguage,
      @Pattern(regexp = "SEARCH|HEALTH_CHECK|TRACKING_RECOMMENDATION") String source,
      UUID recommendationId) {}

  record CancellationReason(
      @Pattern(regexp = "TIME_NO_LONGER_WORKS|FEELING_BETTER|DIFFERENT_SPECIALIST|OTHER")
          String reason) {}

  record Reschedule(@NotNull UUID holdId) {}

  @PostMapping("/v1/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  BookingViews.Booking create(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody BookingRequest body) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SEEKER) {
      throw new SolaceException(BookingError.NOT_A_SEEKER);
    }
    String source = body.source() != null ? body.source() : "SEARCH";
    UUID recommendation = null;
    // A booking answers a tracking invite only while the invite is the seeker's and open (D52).
    if (source.equals("TRACKING_RECOMMENDATION") && body.recommendationId() != null) {
      if (!invites.openFor(account.id(), body.recommendationId())) {
        throw new SolaceException(care.solace.errors.CommonError.REQUEST_INVALID);
      }
      recommendation = body.recommendationId();
    }
    var row =
        bookings.create(
            new Bookings.Booker(account.id(), null, account.appLanguage()),
            body.holdId(),
            body.sessionLanguage(),
            source,
            recommendation);
    return views.of(row, false, LocaleContextHolder.getLocale());
  }

  @GetMapping("/v1/me/bookings")
  List<BookingViews.Booking> mine(@AuthenticationPrincipal Jwt token, @RequestParam String scope) {
    if (!scope.equals("UPCOMING") && !scope.equals("PAST")) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    Account account = accounts.require(token);
    boolean specialist = account.type() == Account.Type.SPECIALIST;
    return store
        .forPerson(
            specialist ? "specialist_id" : "seeker_account_id",
            account.id(),
            scope.equals("UPCOMING"),
            clock.instant())
        .stream()
        .map(b -> views.of(b, specialist, LocaleContextHolder.getLocale()))
        .toList();
  }

  @GetMapping("/v1/bookings/{bookingId}")
  BookingViews.Booking get(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
    var account = accounts.require(token);
    var b = owned(account, bookingId);
    return views.of(b, isSpecialist(account, b), LocaleContextHolder.getLocale());
  }

  @GetMapping("/v1/bookings/{bookingId}/cancellation")
  BookingViews.Quote quote(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
    var account = accounts.require(token);
    var b = owned(account, bookingId);
    return views.of(bookings.quote(b, actor(account, b), clock.instant()));
  }

  @PostMapping("/v1/bookings/{bookingId}/cancellation")
  BookingViews.Booking cancel(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody CancellationReason body) {
    var account = accounts.require(token);
    var b = owned(account, bookingId);
    var cancelled = bookings.cancel(b, actor(account, b), body.reason());
    return views.of(cancelled, isSpecialist(account, b), LocaleContextHolder.getLocale());
  }

  @PostMapping("/v1/bookings/{bookingId}/reschedule")
  BookingViews.Booking reschedule(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody Reschedule body) {
    var account = accounts.require(token);
    var b = owned(account, bookingId);
    if (isSpecialist(account, b)) {
      throw new SolaceException(BookingError.NOT_A_SEEKER);
    }
    return views.of(bookings.reschedule(b, body.holdId()), false, LocaleContextHolder.getLocale());
  }

  /** The booking if this member is its seeker or its specialist; 404 otherwise. */
  private BookingStore.Row owned(Account account, UUID bookingId) {
    return store
        .find(bookingId)
        .filter(
            b -> account.id().equals(b.seekerAccountId()) || account.id().equals(b.specialistId()))
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private static boolean isSpecialist(Account account, BookingStore.Row b) {
    return account.id().equals(b.specialistId());
  }

  private static Bookings.Actor actor(Account account, BookingStore.Row b) {
    return isSpecialist(account, b) ? Bookings.Actor.SPECIALIST : Bookings.Actor.SEEKER;
  }
}
