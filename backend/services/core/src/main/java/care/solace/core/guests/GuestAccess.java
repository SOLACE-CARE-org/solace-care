package care.solace.core.guests;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Guests book without an account (D6, D34): a code proves their phone, and a token scoped to one
 * booking lets them pay, join and rate. Other modules use this API; guest contacts are never shown
 * to them except as an id.
 */
public interface GuestAccess {

  /** Sends a 6-digit code to the phone by SMS, replacing any earlier code. */
  void sendCode(String phone, Locale locale);

  /**
   * Checks the code and returns the guest contact's id (created on first proof). Answers 400 {@code
   * guest.code-rejected} for a wrong, used or expired code; after the last wrong try the code is
   * gone and a new one must be sent.
   */
  UUID verify(String phone, String code, Locale locale);

  /** A token for one booking, valid until {@code expiresAt}. Returned once; only a hash is kept. */
  String issueToken(UUID guestContactId, UUID bookingId, Instant expiresAt);

  /** What a token is for, if it is genuine and not expired. */
  Optional<GuestPass> check(String token, Instant now);

  /** Like {@link #check}, answering 401 {@code guest.token-invalid} otherwise. */
  GuestPass require(String token, Instant now);

  /** The guest's app language, for messages about their booking. */
  Optional<Locale> locale(UUID guestContactId);

  /** The phone the guest proved, for paying with the same number. */
  Optional<String> phone(UUID guestContactId);

  /** The masked phone, as GuestJoin shows it ("+237 6 •• •• •• 00"). */
  Optional<String> maskedPhone(UUID guestContactId);

  record GuestPass(UUID guestContactId, UUID bookingId) {}
}
