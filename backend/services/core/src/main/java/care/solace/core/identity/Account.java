package care.solace.core.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A member account as other modules see it: never a name, a phone number or an email. The id is the
 * pseudonymous subject id used in events and in Tracking and AI.
 */
public record Account(UUID id, Type type, Status status, Locale appLanguage, Instant verifiedAt) {

  /** Seekers and specialists; staff live in the other realm and have no member account. */
  public enum Type {
    SEEKER,
    SPECIALIST
  }

  /** PENDING until the first sign-in with a code proves the contact. */
  public enum Status {
    PENDING,
    ACTIVE,
    SUSPENDED
  }
}
