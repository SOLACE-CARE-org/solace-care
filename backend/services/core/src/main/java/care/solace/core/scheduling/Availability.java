package care.solace.core.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask of scheduling: a specialist's starts, computed on read from weekly
 * hours less bookings, holds and time off; 10-minute holds during checkout; and reservations that
 * an exclusion constraint keeps from overlapping (System Design, Scheduling).
 */
public interface Availability {

  /** How long a hold lasts. A decision, not a setting (CLAUDE.md, Settings). */
  int HOLD_MINUTES = 10;

  /** The time zone the specialist's weekly hours are in, once saved. */
  Optional<String> timeZone(UUID specialistId);

  /** True once the specialist has saved weekly hours (SpecAvailability). */
  boolean hasWeeklyHours(UUID specialistId);

  /** The soonest free start for a session of {@code minutes}, within the next two weeks. */
  Optional<Instant> nextAvailable(UUID specialistId, Instant now, int minutes);

  /**
   * Starts for a session of {@code minutes} over {@code days} days from {@code from} (in the
   * specialist's time zone), taken ones included and marked. Empty without weekly hours.
   */
  Optional<Week> slots(UUID specialistId, LocalDate from, int days, int minutes, Instant now);

  /**
   * Holds a free start for {@link #HOLD_MINUTES}; answers 409 {@code slot.taken} when it is not one
   * of the free starts any more.
   */
  Hold hold(UUID specialistId, Instant start, int minutes, String modality, Instant now);

  Optional<Hold> findHold(UUID holdId);

  /** Lets a hold go; nothing happens if it already expired. */
  void release(UUID holdId);

  /** Time off between two instants, for the specialist's calendar. */
  List<TimeOff> timeOff(UUID specialistId, Instant from, Instant to);

  record Week(String timeZone, List<Slot> slots) {}

  record Slot(Instant start, Instant end, boolean available) {}

  record Hold(
      UUID id, UUID specialistId, Instant start, Instant end, String modality, Instant expiresAt) {}

  record TimeOff(UUID id, Instant start, Instant end, String reason) {}
}
