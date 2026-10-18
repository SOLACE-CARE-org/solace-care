package care.solace.core.payments;

import java.util.UUID;

/**
 * Published inside Core when a payout reaches the specialist's wallet or fails, for Payout sent and
 * Payout failed (epic 9).
 */
public record PayoutSettled(
    UUID specialistId,
    long amount,
    String method,
    boolean paid,
    java.time.LocalDate periodStart,
    java.time.LocalDate periodEnd) {}
