package care.solace.keycloak.otp;

import java.util.Locale;
import java.util.Objects;

/**
 * A phone number or email a code is sent to.
 *
 * <p>{@link #toString()} returns the masked form, so a contact never reaches a log in full.
 */
public record Contact(Kind kind, String value) {

  /** How the code is delivered. */
  public enum Kind {
    PHONE,
    EMAIL
  }

  public Contact {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(value);
  }

  /**
   * Parses what the person typed. Anything with an {@code @} is an email, lower-cased. Anything
   * else is a phone number in E.164 form ({@code +} and 8 to 15 digits) once spaces, dots and
   * dashes are removed. Returns {@code null} when the input is neither.
   */
  public static Contact parse(String input) {
    if (input == null) {
      return null;
    }
    String trimmed = input.trim();
    if (trimmed.contains("@")) {
      String email = trimmed.toLowerCase(Locale.ROOT);
      return email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? new Contact(Kind.EMAIL, email) : null;
    }
    String phone = trimmed.replaceAll("[\\s.\\-()]", "");
    return phone.matches("\\+[1-9]\\d{7,14}") ? new Contact(Kind.PHONE, phone) : null;
  }

  /** Masked for logs: {@code +237******00} or {@code j***@example.com}. */
  public String masked() {
    if (kind == Kind.PHONE) {
      int visibleStart = Math.min(4, value.length());
      int visibleEnd = Math.max(visibleStart, value.length() - 2);
      return value.substring(0, visibleStart)
          + "*".repeat(visibleEnd - visibleStart)
          + value.substring(visibleEnd);
    }
    int at = value.indexOf('@');
    return value.charAt(0) + "***" + value.substring(at);
  }

  @Override
  public String toString() {
    return masked();
  }
}
