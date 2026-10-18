package care.solace.core.invites;

import java.util.UUID;

/** Whether a booking may answer a tracking invite (D52). */
public interface TrackingInvites {

  /** True when the invite is the account's and still open. */
  boolean openFor(UUID accountId, UUID recommendationId);
}
