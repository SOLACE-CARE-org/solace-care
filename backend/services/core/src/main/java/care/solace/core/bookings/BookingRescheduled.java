package care.solace.core.bookings;

import java.time.Instant;
import java.util.UUID;

/** Published inside Core when a booking moves to a new start, for the Rescheduled message. */
public record BookingRescheduled(UUID bookingId, Instant start) {}
