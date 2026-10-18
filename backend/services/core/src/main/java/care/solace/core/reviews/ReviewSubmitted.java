package care.solace.core.reviews;

import java.util.UUID;

/**
 * Published inside Core when a written review arrives, for the safety monitor's check (epic 10). It
 * carries the id only: the text stays in Core (CLAUDE.md, no free text in events).
 */
public record ReviewSubmitted(UUID reviewId) {}
