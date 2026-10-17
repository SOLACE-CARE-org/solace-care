package care.solace.core.scheduling;

import java.util.UUID;

/** What other modules may ask about a specialist's availability. */
public interface Availability {

  /** True once the specialist has saved weekly hours (SpecAvailability). */
  boolean hasWeeklyHours(UUID specialistId);
}
