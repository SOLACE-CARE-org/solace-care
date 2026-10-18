package care.solace.core.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside Core when a payment request fails while the booking still waits, for the Payment
 * not completed message (epic 9): the time stays held until {@code payBy}.
 */
public record PaymentNotCompleted(UUID bookingId, UUID subjectId, Instant payBy) {}
