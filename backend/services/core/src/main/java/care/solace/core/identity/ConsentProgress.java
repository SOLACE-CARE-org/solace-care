package care.solace.core.identity;

import java.util.UUID;

/**
 * Whether a member has made their privacy choices (sign-up step 3), so {@code /v1/me} can tell the
 * app where to resume. Implemented by the privacy module, which owns consent records; declared here
 * so identity does not depend on privacy.
 */
public interface ConsentProgress {

  boolean recordedAny(UUID accountId);
}
