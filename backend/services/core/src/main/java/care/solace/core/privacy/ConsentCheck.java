package care.solace.core.privacy;

import java.util.UUID;

/** Whether a member's latest choice for a purpose is yes (System Design, Controls). */
public interface ConsentCheck {

  /**
   * @param purpose a {@link Purpose} name, e.g. HEALTH_CHECK
   */
  boolean granted(UUID accountId, String purpose);
}
