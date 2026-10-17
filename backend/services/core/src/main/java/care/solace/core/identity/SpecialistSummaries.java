package care.solace.core.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code /v1/me} shows for a specialist. The specialists module implements it, so identity
 * does not depend on that module.
 */
public interface SpecialistSummaries {

  /** The specialist's name and application status; empty when there is no application. */
  Optional<Summary> of(UUID accountId);

  /**
   * @param applicationStatus one of the contract's ApplicationStatus values
   */
  record Summary(String fullName, String applicationStatus) {}
}
