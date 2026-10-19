package care.solace.core.sessions;

import care.solace.core.admin.FeatureFlags;
import care.solace.core.bookings.BookingRecords;
import care.solace.errors.SolaceException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Joining a session by phone through LiveKit SIP (System Design, Audio and video: phone dial-in for
 * seekers without data; Phase 4, D59). Each booking gets a six-digit PIN; while joining is open,
 * from 10 minutes before the start to 15 minutes after the end, a dispatch rule sends a caller who
 * enters it to the booking's room, where they count as the seeker. Audio only.
 */
@Service
class DialIns {

  private static final Logger LOG = LoggerFactory.getLogger(DialIns.class);

  record DialIn(String number, String pin, Instant opensAt, Instant closesAt) {}

  private final JdbcClient db;
  private final LiveKit liveKit;
  private final BookingRecords bookings;
  private final FeatureFlags flags;
  private final String number;
  private final List<String> trunks;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  DialIns(
      JdbcClient db,
      LiveKit liveKit,
      BookingRecords bookings,
      FeatureFlags flags,
      @Value("${solace.sessions.dial-in.number:}") String number,
      @Value("${solace.sessions.dial-in.trunks:}") String trunks,
      Clock clock) {
    this.db = db;
    this.liveKit = liveKit;
    this.bookings = bookings;
    this.flags = flags;
    this.number = number.strip();
    this.trunks =
        Arrays.stream(trunks.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    this.clock = clock;
  }

  /** The number and the booking's PIN; refused when the flag is off or no number is set up. */
  DialIn dialIn(BookingRecords.Info b) {
    if (!flags.enabled("phoneDialIn") || number.isEmpty()) {
      throw new SolaceException(SessionError.NO_DIAL_IN);
    }
    Instant closes = b.end().plus(Sessions.TOKEN_AFTER_END);
    if ((!b.status().equals("CONFIRMED") && !b.status().equals("IN_PROGRESS"))
        || !clock.instant().isBefore(closes)) {
      throw new SolaceException(SessionError.NOT_JOINABLE);
    }
    return new DialIn(number, pin(b.id()), b.start().minus(Sessions.JOIN_OPENS), closes);
  }

  private String pin(UUID bookingId) {
    for (int attempt = 0; attempt < 10; attempt++) {
      var existing =
          db.sql("SELECT pin FROM session_dial_in WHERE booking_id = :b")
              .param("b", bookingId)
              .query(String.class)
              .optional();
      if (existing.isPresent()) {
        return existing.get();
      }
      String pin = String.format("%06d", random.nextInt(1_000_000));
      try {
        db.sql(
                """
                INSERT INTO session_dial_in (booking_id, pin, created_at) VALUES (:b, :p, :now)
                ON CONFLICT (booking_id) DO NOTHING
                """)
            .param("b", bookingId)
            .param("p", pin)
            .param("now", Timestamp.from(clock.instant()))
            .update();
      } catch (DuplicateKeyException e) {
        // The PIN is in use by another open booking: draw again.
      }
    }
    throw new IllegalStateException("No free dial-in PIN");
  }

  /**
   * Every minute: open rules for bookings whose joining opened, close those whose joining ended.
   */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  void sweep() {
    try {
      sweepNow();
    } catch (RuntimeException e) {
      LOG.warn("The dial-in sweep failed", e);
    }
  }

  void sweepNow() {
    Instant now = clock.instant();
    record Row(UUID bookingId, String pin, String ruleId) {}
    for (Row r :
        db.sql("SELECT booking_id, pin, rule_id FROM session_dial_in WHERE closed_at IS NULL")
            .query(
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .list()) {
      var b = bookings.find(r.bookingId());
      boolean over =
          b.isEmpty()
              || !(b.get().status().equals("CONFIRMED") || b.get().status().equals("IN_PROGRESS"))
              || !now.isBefore(b.get().end().plus(Sessions.TOKEN_AFTER_END));
      if (over) {
        if (r.ruleId() == null || liveKit.deleteDialInRule(r.ruleId(), now)) {
          db.sql("UPDATE session_dial_in SET closed_at = :now WHERE booking_id = :b")
              .param("b", r.bookingId())
              .param("now", Timestamp.from(now))
              .update();
        }
      } else if (r.ruleId() == null
          && !now.isBefore(b.get().start().minus(Sessions.JOIN_OPENS))
          && !trunks.isEmpty()) {
        String rule = liveKit.createDialInRule(r.bookingId().toString(), r.pin(), trunks, now);
        if (rule != null) {
          db.sql("UPDATE session_dial_in SET rule_id = :r WHERE booking_id = :b")
              .param("b", r.bookingId())
              .param("r", rule)
              .update();
        }
      }
    }
  }
}
