package care.solace.core.guests;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Guest contacts for the People screens (admin screens 9 and 10; D45). */
public interface GuestDirectory {

  Optional<Guest> byId(UUID id);

  Optional<Guest> byPhone(String e164);

  /** The newest guests first, not yet claimed by an account. */
  List<Guest> newest(int limit);

  /** Removes the phone and every link, as retention does (D40). Bookings keep their record. */
  void forget(UUID id, Instant now);

  /**
   * @param phone null once forgotten
   */
  record Guest(UUID id, String phone, Locale locale, Instant createdAt, UUID claimedBy) {}
}
