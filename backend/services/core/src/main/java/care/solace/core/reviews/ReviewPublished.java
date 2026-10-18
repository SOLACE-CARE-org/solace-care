package care.solace.core.reviews;

import java.util.UUID;

/** Published inside Core when a review appears on a profile, for New review (epic 9). */
public record ReviewPublished(UUID reviewId, UUID specialistId) {}
