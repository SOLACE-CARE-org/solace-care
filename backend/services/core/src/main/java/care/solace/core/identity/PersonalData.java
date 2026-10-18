package care.solace.core.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * What one module holds about a member, for their copy of their data and for erasure (D40). Each
 * module that keeps personal data implements it for its own tables; Privacy only gathers. Every
 * module already depends on identity, so this port adds no dependency.
 */
public interface PersonalData {

  /** The section of the export, e.g. "bookings". */
  String section();

  /** This module's data about the account, as JSON-ready values; null when there is none. */
  Object export(UUID accountId);

  /**
   * Why erasure must wait, as an error code, or empty. Checked for every module before anything is
   * erased (e.g. a paid session still to come).
   */
  default Optional<String> blocksErasure(UUID accountId) {
    return Optional.empty();
  }

  /** Erases or strips this module's data, inside the erasure transaction. */
  void erase(UUID accountId);
}
