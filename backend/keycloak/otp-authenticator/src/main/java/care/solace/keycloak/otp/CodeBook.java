package care.solace.keycloak.otp;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and checks one-time codes for one realm. Only a hash of each code is stored, with its
 * expiry and the number of wrong attempts.
 *
 * <p>The expiry is kept in the entry and checked here as well as through the store's lifespan,
 * because a store may reset the lifespan when an entry is replaced.
 */
public final class CodeBook {

  /** Where codes live between the two sign-in requests. Keycloak's single-use object store. */
  public interface Store {
    Map<String, String> get(String key);

    void put(String key, long lifespanSeconds, Map<String, String> notes);

    void replace(String key, Map<String, String> notes);

    void remove(String key);
  }

  /** Outcome of checking a submitted code. */
  public enum Result {
    /** The code is right; it has been used up. */
    ACCEPTED,
    /** The code is wrong; more attempts are allowed. */
    WRONG,
    /** No live code: never issued, expired, already used, or too many wrong attempts. */
    NO_LIVE_CODE
  }

  static final String HASH = "hash";
  static final String ATTEMPTS = "attempts";
  static final String ISSUED_AT = "issuedAt";
  static final String EXPIRES_AT = "expiresAt";

  private final Store store;
  private final OtpSettings settings;
  private final Clock clock;
  private final String realmId;

  public CodeBook(Store store, OtpSettings settings, Clock clock, String realmId) {
    this.store = store;
    this.settings = settings;
    this.clock = clock;
    this.realmId = realmId;
  }

  /**
   * Issues a new code for the user, replacing any earlier one. Returns empty when the last code was
   * issued less than the resend delay ago, so codes cannot be requested in a loop.
   */
  public Optional<String> issue(String userId) {
    Instant now = clock.instant();
    Map<String, String> current = live(userId, now);
    if (current != null
        && Instant.parse(current.get(ISSUED_AT)).plus(settings.resendAfter()).isAfter(now)) {
      return Optional.empty();
    }
    String code = Codes.generate(settings.codeLength());
    Map<String, String> entry = new HashMap<>();
    entry.put(HASH, Codes.hash(userId, code));
    entry.put(ATTEMPTS, "0");
    entry.put(ISSUED_AT, now.toString());
    entry.put(EXPIRES_AT, now.plus(settings.codeTtl()).toString());
    store.put(key(userId), settings.codeTtl().toSeconds(), entry);
    return Optional.of(code);
  }

  /** Checks a submitted code. A right code is used up; too many wrong ones discard the code. */
  public Result verify(String userId, String code) {
    Instant now = clock.instant();
    Map<String, String> current = live(userId, now);
    if (current == null) {
      return Result.NO_LIVE_CODE;
    }
    if (Codes.matches(userId, code, current.get(HASH))) {
      store.remove(key(userId));
      return Result.ACCEPTED;
    }
    int attempts = Integer.parseInt(current.get(ATTEMPTS)) + 1;
    if (attempts >= settings.maxAttempts()) {
      store.remove(key(userId));
      return Result.NO_LIVE_CODE;
    }
    Map<String, String> updated = new HashMap<>(current);
    updated.put(ATTEMPTS, Integer.toString(attempts));
    store.replace(key(userId), updated);
    return Result.WRONG;
  }

  private Map<String, String> live(String userId, Instant now) {
    Map<String, String> entry = store.get(key(userId));
    if (entry == null) {
      return null;
    }
    if (!Instant.parse(entry.get(EXPIRES_AT)).isAfter(now)) {
      store.remove(key(userId));
      return null;
    }
    return entry;
  }

  private String key(String userId) {
    return "solace-otp:" + realmId + ":" + userId;
  }
}
