package care.solace.errors;

/**
 * A stable, machine-readable error. Apps key their own screen copy on {@link #code()}; support
 * reads the {@link #reference(int)} people quote ("Reference SES-503" on the error screens).
 *
 * <p>Each service declares its own codes as an enum implementing this interface. The localised
 * title is the message {@code error.<code>.title}, in EN and FR.
 */
public interface ErrorCode {

  /** Dotted, lower-case, stable: {@code booking.slot-taken}. Never contains user data. */
  String code();

  /** HTTP status for this error. */
  int status();

  /** Short upper-case area for the support reference, e.g. {@code SES} for sessions. */
  String area();

  /** The reference people quote to support: {@code SES-503}. */
  default String reference(int status) {
    return area() + "-" + status;
  }

  /** Message key of the localised title. */
  default String titleKey() {
    return "error." + code() + ".title";
  }
}
