package care.solace.core.bookings;

import java.util.UUID;

/**
 * Published in the booking's transaction when a seeker ticks "Share my answers with the specialist
 * I book" (Matches.dc.html; D55). The health check module records the share; nothing leaves Core.
 */
public record HealthCheckShared(UUID bookingId, UUID responseId, UUID seekerAccountId) {}
