package care.solace.core.sessions;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.bookings.BookingRecords.NoShowBy;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sessions (System Design, Join flow; D18, D36): a room per booking named after it, join tokens
 * from 10 minutes before the start until 15 minutes after the end, and attendance from the media
 * server that starts, completes or marks a no-show on the booking.
 */
@Service
@EnableConfigurationProperties(SessionSettings.class)
class Sessions {

  /** Joining opens 10 minutes before the start (D18). */
  static final Duration JOIN_OPENS = Duration.ofMinutes(10);

  /** Tokens last until 15 minutes after the scheduled end (System Design, Join flow). */
  static final Duration TOKEN_AFTER_END = Duration.ofMinutes(15);

  private static final Logger LOG = LoggerFactory.getLogger(Sessions.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  enum Role {
    SEEKER,
    SPECIALIST
  }

  record Join(
      String url, String token, String room, Role role, String modality, Instant expiresAt) {}

  private final LiveKit liveKit;
  private final BookingRecords bookings;
  private final JdbcClient db;
  private final SessionSettings settings;
  private final care.solace.core.admin.PlatformSettings platform;
  private final Clock clock;
  private final Scribe scribe;
  private final Set<String> rooms = ConcurrentHashMap.newKeySet();

  Sessions(
      LiveKit liveKit,
      BookingRecords bookings,
      JdbcClient db,
      SessionSettings settings,
      care.solace.core.admin.PlatformSettings platform,
      Clock clock,
      Scribe scribe) {
    this.liveKit = liveKit;
    this.bookings = bookings;
    this.db = db;
    this.settings = settings;
    this.platform = platform;
    this.clock = clock;
    this.scribe = scribe;
  }

  /** A token for the booking's room; the caller has already been checked as {@code role}. */
  Join join(BookingRecords.Info b, Role role) {
    Instant now = clock.instant();
    if (!b.status().equals("CONFIRMED") && !b.status().equals("IN_PROGRESS")) {
      throw new SolaceException(SessionError.NOT_JOINABLE);
    }
    if (now.isBefore(b.start().minus(JOIN_OPENS))) {
      throw new SolaceException(SessionError.NOT_OPEN_YET);
    }
    Instant expires = b.end().plus(TOKEN_AFTER_END);
    if (!now.isBefore(expires)) {
      throw new SolaceException(SessionError.NOT_JOINABLE);
    }
    String room = b.id().toString();
    if (rooms.add(room)) {
      liveKit.createRoom(room, Duration.between(now, expires), now);
    }
    boolean video = b.modality().equals("VIDEO");
    String token = liveKit.joinToken(room, role.name().toLowerCase(), video, now, expires);
    return new Join(liveKit.url(), token, room, role, b.modality(), expires);
  }

  // --- Attendance -------------------------------------------------------------------------------

  /** A verified webhook from the media server. */
  void webhook(byte[] body) {
    JsonNode event;
    try {
      event = JSON.readTree(body);
    } catch (Exception e) {
      return;
    }
    String type = event.path("event").asText();
    String kind =
        switch (type) {
          case "participant_joined" -> "JOINED";
          case "participant_left" -> "LEFT";
          case "room_finished" -> "ROOM_FINISHED";
          default -> null;
        };
    Optional<UUID> bookingId = uuid(event.path("room").path("name").asText());
    if (kind == null || bookingId.isEmpty()) {
      return;
    }
    Role role = null;
    if (!kind.equals("ROOM_FINISHED")) {
      String identity = event.path("participant").path("identity").asText();
      role =
          switch (identity) {
            case "seeker" -> Role.SEEKER;
            case "specialist" -> Role.SPECIALIST;
            default -> null;
          };
      if (role == null) {
        return;
      }
    }
    long seconds = event.path("createdAt").asLong(0);
    Instant at = seconds > 0 ? Instant.ofEpochSecond(seconds) : clock.instant();
    String eventId =
        event.path("id").asText(type + ":" + bookingId.get() + ":" + role + ":" + seconds);
    boolean first =
        db.sql(
                    """
                    INSERT INTO session_event (event_id, booking_id, role, kind, at)
                    VALUES (:e, :b, :r, :k, :at) ON CONFLICT (event_id) DO NOTHING
                    """)
                .param("e", eventId)
                .param("b", bookingId.get())
                .param("r", role == null ? null : role.name())
                .param("k", kind)
                .param("at", Timestamp.from(at))
                .update()
            == 1;
    if (!first) {
      return;
    }
    UUID id = bookingId.get();
    List<String> joined = joined(id);
    if (kind.equals("JOINED") && joined.size() == 2) {
      bookings.start(id, at);
      bookings.find(id).ifPresent(scribe::bothJoined);
    } else if (kind.equals("ROOM_FINISHED") && joined.size() == 2) {
      bookings
          .find(id)
          .filter(b -> b.status().equals("CONFIRMED"))
          .ifPresent(b -> bookings.start(id, at));
      bookings.complete(id, at);
      bookings.find(id).ifPresent(scribe::ended);
    }
  }

  /** Every minute: settle sessions the webhooks did not (D36). */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  void settle() {
    try {
      settleNow();
    } catch (RuntimeException e) {
      LOG.warn("Settling sessions failed", e);
    }
  }

  void settleNow() {
    Instant now = clock.instant();
    for (var b :
        bookings.confirmedStartedBefore(
            now.minus(
                platform.duration("solace.sessions.no-show-after", settings.noShowAfter())))) {
      List<String> joined = joined(b.id());
      if (joined.size() == 2) {
        bookings.start(b.id(), now);
      } else if (joined.contains("SPECIALIST")) {
        bookings.noShow(b.id(), NoShowBy.SEEKER, now);
      } else if (joined.contains("SEEKER")) {
        bookings.noShow(b.id(), NoShowBy.SPECIALIST, now);
      } else {
        db.sql(
                """
                INSERT INTO session_flag (booking_id, reason, created_at)
                VALUES (:b, 'BOTH_ABSENT', :now) ON CONFLICT DO NOTHING
                """)
            .param("b", b.id())
            .param("now", Timestamp.from(now))
            .update();
      }
    }
    for (var b :
        bookings.inProgressEndedBefore(
            now.minus(
                platform.duration("solace.sessions.complete-after", settings.completeAfter())))) {
      bookings.complete(b.id(), now);
      bookings.find(b.id()).ifPresent(scribe::ended);
    }
  }

  /** The roles that ever joined the booking's room. */
  List<String> joined(UUID bookingId) {
    return db.sql(
            """
            SELECT DISTINCT role FROM session_event
             WHERE booking_id = :b AND kind = 'JOINED' ORDER BY role
            """)
        .param("b", bookingId)
        .query(String.class)
        .list();
  }

  private static Optional<UUID> uuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
