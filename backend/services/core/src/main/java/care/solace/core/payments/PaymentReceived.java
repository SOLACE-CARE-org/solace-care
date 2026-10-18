package care.solace.core.payments;

import java.util.UUID;

/** Published inside Core when a booking is paid, for the Payment received message (epic 9). */
public record PaymentReceived(UUID bookingId, UUID subjectId, long amount) {}
