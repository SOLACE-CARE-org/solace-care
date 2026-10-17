package care.solace.keycloak.otp;

import java.time.Duration;
import java.util.Map;

/**
 * Settings for one-time sign-in codes, read from the authenticator's config in the realm.
 *
 * <p>The defaults are placeholders, not decisions: the code lifetime is the {@code [10]} minutes in
 * the notification copy, and {@code [3]} wrong tries before a new code is sent come from the
 * StateCodeWrong screen; code length and resend delay are not in the specs.
 *
 * @param codeLength digits in a code
 * @param codeTtl how long a code stays valid
 * @param maxAttempts wrong entries allowed before the code is replaced by a new one
 * @param resendAfter minimum gap before another code is sent to the same account
 * @param sender which {@link CodeSender} delivers the code
 */
public record OtpSettings(
    int codeLength, Duration codeTtl, int maxAttempts, Duration resendAfter, String sender) {

  public static final String CODE_LENGTH = "codeLength";
  public static final String CODE_TTL_SECONDS = "codeTtlSeconds";
  public static final String MAX_ATTEMPTS = "maxAttempts";
  public static final String RESEND_AFTER_SECONDS = "resendAfterSeconds";
  public static final String SENDER = "sender";

  public static final OtpSettings DEFAULTS =
      new OtpSettings(6, Duration.ofMinutes(10), 3, Duration.ofSeconds(60), LoggingCodeSender.ID);

  public OtpSettings {
    if (codeLength < 4 || codeLength > 10) {
      throw new IllegalArgumentException("codeLength must be between 4 and 10");
    }
    if (codeTtl.isNegative() || codeTtl.isZero()) {
      throw new IllegalArgumentException("codeTtl must be positive");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    if (resendAfter.isNegative()) {
      throw new IllegalArgumentException("resendAfter must not be negative");
    }
  }

  /** Reads settings from an authenticator config map; missing or blank values use the defaults. */
  public static OtpSettings from(Map<String, String> config) {
    if (config == null) {
      return DEFAULTS;
    }
    return new OtpSettings(
        intValue(config, CODE_LENGTH, DEFAULTS.codeLength()),
        Duration.ofSeconds(
            intValue(config, CODE_TTL_SECONDS, (int) DEFAULTS.codeTtl().toSeconds())),
        intValue(config, MAX_ATTEMPTS, DEFAULTS.maxAttempts()),
        Duration.ofSeconds(
            intValue(config, RESEND_AFTER_SECONDS, (int) DEFAULTS.resendAfter().toSeconds())),
        stringValue(config, SENDER, DEFAULTS.sender()));
  }

  private static int intValue(Map<String, String> config, String key, int fallback) {
    String value = config.get(key);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }

  private static String stringValue(Map<String, String> config, String key, String fallback) {
    String value = config.get(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
