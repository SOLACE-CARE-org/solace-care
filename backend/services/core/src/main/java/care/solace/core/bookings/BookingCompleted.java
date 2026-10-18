package care.solace.core.bookings;

import java.time.Instant;
import java.util.UUID;

/** Published inside Core when a session completes: Payments releases the money (D32). */
public record BookingCompleted(UUID bookingId, UUID specialistId, Instant at) {}
