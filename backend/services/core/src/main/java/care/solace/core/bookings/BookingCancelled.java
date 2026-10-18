package care.solace.core.bookings;

import java.util.UUID;

/**
 * Published inside Core when a booking is cancelled, for Payments to refund what the policy gave
 * (epic 6). In-process; Kafka gets core.booking.cancelled with ids and codes only.
 */
public record BookingCancelled(UUID bookingId, long refundAmount, String cancelledBy) {}
