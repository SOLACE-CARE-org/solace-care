package care.solace.core.bookings;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside Core when a session ends in a no-show, with the refund percent the booking's
 * policy gives (D33): Payments proposes that refund and releases the rest.
 */
public record BookingNoShow(
    UUID bookingId,
    BookingRecords.NoShowBy who,
    int refundPercent,
    int policyVersion,
    Instant at) {}
