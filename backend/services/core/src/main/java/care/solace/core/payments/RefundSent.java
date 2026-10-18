package care.solace.core.payments;

import java.util.UUID;

/** Published inside Core when a refund reaches the wallet, for the Refund sent message (epic 9). */
public record RefundSent(UUID bookingId, UUID subjectId, long amount, String method) {}
