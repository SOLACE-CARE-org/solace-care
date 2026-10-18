package care.solace.core.specialists;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Specialists' ratings as search and profiles show them (D37). Ratings and reviews implements it,
 * so the directory never reads that module's tables.
 */
public interface SpecialistRatings {

  /** The ratings of those with enough of them to show; others are absent. */
  Map<UUID, Rating> shown(Collection<UUID> specialistIds);

  /**
   * @param average one decimal
   */
  record Rating(double average, int count) {}
}
