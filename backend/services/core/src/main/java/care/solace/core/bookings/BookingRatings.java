package care.solace.core.bookings;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which bookings their seeker has rated, for the Rate button (D37). Ratings and reviews implements
 * it, so bookings never reads that module's tables.
 */
public interface BookingRatings {

  Set<UUID> rated(Collection<UUID> bookingIds);
}
