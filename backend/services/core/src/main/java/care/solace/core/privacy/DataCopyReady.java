package care.solace.core.privacy;

import java.util.UUID;

/** Published inside Core when a copy of a person's data is ready, for Data copy ready (D40). */
public record DataCopyReady(UUID accountId, long days) {}
