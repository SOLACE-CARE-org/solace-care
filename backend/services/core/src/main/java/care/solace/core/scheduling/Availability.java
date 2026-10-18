package care.solace.core.scheduling;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask about a specialist's availability. */
public interface Availability {

  /** True once the specialist has saved weekly hours (SpecAvailability). */
  boolean hasWeeklyHours(UUID specialistId);

  /**
   * The soonest start for a session of {@code minutes} after {@code now}, within the next two
   * weeks. From weekly hours, notice and break; bookings and holds are subtracted with epic 4.
   */
  Optional<Instant> nextAvailable(UUID specialistId, Instant now, int minutes);
}
