package care.solace.core.sessions;

import java.util.UUID;

/**
 * Published inside Core when the seeker is in the room before the specialist (Messaging tells
 * them).
 */
public record SeekerWaiting(UUID bookingId, UUID specialistId) {}
