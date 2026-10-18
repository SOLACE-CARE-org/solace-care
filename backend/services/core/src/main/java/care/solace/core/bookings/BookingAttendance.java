package care.solace.core.bookings;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who joined a booking's room and when, by role only (D36), and the flags Core raises when
 * attendance cannot settle a booking. Sessions implements it, so bookings never reads its tables.
 */
public interface BookingAttendance {

  /** Joins, leaves and the room closing, oldest first. */
  List<Event> events(UUID bookingId);

  /** The open flag on a booking, if Core raised one (neither person joined). */
  Optional<Instant> openFlag(UUID bookingId);

  /** Which of these bookings have an open flag. */
  java.util.Set<UUID> flagged(Collection<UUID> bookingIds);

  /** Every booking with an open flag; few at any time. */
  java.util.Set<UUID> allFlagged();

  /** Who is alone in the room right now, for bookings in session or about to be. */
  Map<UUID, String> waiting(Collection<UUID> bookingIds);

  /** Closes the booking's flag with how Support resolved it. */
  void resolveFlag(UUID bookingId, String outcome, Instant now);

  /**
   * @param role SEEKER or SPECIALIST, absent when the room closed
   * @param kind JOINED, LEFT or ROOM_FINISHED
   */
  record Event(Instant at, String role, String kind) {}
}
