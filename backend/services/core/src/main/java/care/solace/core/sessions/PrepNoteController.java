package care.solace.core.sessions;

import care.solace.audit.AuditLog;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The specialist's private prep notes for one booking, on the pre-session brief
 * (SessionBrief.dc.html; D56). Only the booking's specialist reads and writes them; nothing else
 * reads them, and every read is audited.
 */
@RestController
class PrepNoteController {

  private final Accounts accounts;
  private final BookingRecords bookings;
  private final JdbcClient db;
  private final AuditLog audit;
  private final Clock clock;

  PrepNoteController(
      Accounts accounts, BookingRecords bookings, JdbcClient db, AuditLog audit, Clock clock) {
    this.accounts = accounts;
    this.bookings = bookings;
    this.db = db;
    this.audit = audit;
    this.clock = clock;
  }

  record PrepNote(String body, Instant updatedAt) {}

  record Body(@NotNull @Size(max = 4000) String body) {}

  @GetMapping("/v1/specialist/bookings/{bookingId}/prep-note")
  ResponseEntity<PrepNote> get(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
    var booking = mine(token, bookingId);
    var note =
        db.sql("SELECT body, updated_at FROM session_prep_note WHERE booking_id = :b")
            .param("b", bookingId)
            .query(
                (rs, n) ->
                    new PrepNote(rs.getString("body"), rs.getTimestamp("updated_at").toInstant()))
            .optional();
    if (note.isEmpty()) {
      return ResponseEntity.noContent().build();
    }
    audit.read("session.prep-note.read", "booking", bookingId.toString(), subject(booking));
    return ResponseEntity.ok(note.get());
  }

  @PutMapping("/v1/specialist/bookings/{bookingId}/prep-note")
  PrepNote put(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody Body body) {
    var booking = mine(token, bookingId);
    Instant now = clock.instant();
    db.sql(
            """
            INSERT INTO session_prep_note (booking_id, specialist_id, body, updated_at)
            VALUES (:b, :s, :body, :now)
            ON CONFLICT (booking_id) DO UPDATE SET body = EXCLUDED.body, updated_at = EXCLUDED.updated_at
            """)
        .param("b", bookingId)
        .param("s", booking.specialistId())
        .param("body", body.body())
        .param("now", Timestamp.from(now))
        .update();
    audit.adminAction("session.prep-note.save", "booking", bookingId.toString(), Map.of());
    return new PrepNote(body.body(), now);
  }

  private BookingRecords.Info mine(Jwt token, UUID bookingId) {
    Account me = accounts.require(token);
    var booking =
        bookings.find(bookingId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (me.type() != Account.Type.SPECIALIST || !booking.specialistId().equals(me.id())) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return booking;
  }

  private static String subject(BookingRecords.Info b) {
    UUID s = b.seekerAccountId() != null ? b.seekerAccountId() : b.guestContactId();
    return s == null ? null : s.toString();
  }
}
