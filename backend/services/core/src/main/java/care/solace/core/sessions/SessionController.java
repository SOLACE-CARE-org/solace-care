package care.solace.core.sessions;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.guests.GuestAccess;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Joining a session and the media server's webhooks (contracts/openapi/core.yaml, tag sessions).
 */
@RestController
class SessionController {

  private final Sessions sessions;
  private final LiveKit liveKit;
  private final BookingRecords bookings;
  private final Accounts accounts;
  private final GuestAccess guests;
  private final Clock clock;

  SessionController(
      Sessions sessions,
      LiveKit liveKit,
      BookingRecords bookings,
      Accounts accounts,
      GuestAccess guests,
      Clock clock) {
    this.sessions = sessions;
    this.liveKit = liveKit;
    this.bookings = bookings;
    this.accounts = accounts;
    this.guests = guests;
    this.clock = clock;
  }

  /** Only the booked seeker and the specialist may join; anyone else gets 404 (CO-18). */
  @PostMapping("/v1/bookings/{bookingId}/join")
  Sessions.Join join(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
    var account = accounts.require(token);
    var booking =
        bookings.find(bookingId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (account.id().equals(booking.specialistId())) {
      return sessions.join(booking, Sessions.Role.SPECIALIST);
    }
    if (account.id().equals(booking.seekerAccountId())) {
      return sessions.join(booking, Sessions.Role.SEEKER);
    }
    throw new SolaceException(CommonError.NOT_FOUND);
  }

  @PostMapping("/v1/guest/booking/join")
  Sessions.Join joinAsGuest(@RequestHeader("X-Guest-Token") String token) {
    var pass = guests.require(token, clock.instant());
    var booking =
        bookings
            .find(pass.bookingId())
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return sessions.join(booking, Sessions.Role.SEEKER);
  }

  @PostMapping(
      value = "/v1/webhooks/livekit",
      consumes = {"application/webhook+json", "application/json"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void webhook(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody byte[] body) {
    if (!liveKit.verifyWebhook(authorization, body)) {
      throw new SolaceException(SessionError.SIGNATURE_INVALID);
    }
    sessions.webhook(body);
  }
}
