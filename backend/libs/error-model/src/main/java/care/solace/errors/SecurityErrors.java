package care.solace.errors;

/**
 * Maps Spring Security exceptions without making Spring Security a hard dependency: services
 * without it (none today) still load this library.
 */
final class SecurityErrors {

  private static final boolean PRESENT = isPresent();

  private SecurityErrors() {}

  /** The common error for a security exception, or {@code null} if it is not one. */
  static CommonError map(Exception e) {
    if (!PRESENT) {
      return null;
    }
    if (e instanceof org.springframework.security.access.AccessDeniedException) {
      return CommonError.ACCESS_DENIED;
    }
    if (e instanceof org.springframework.security.core.AuthenticationException) {
      return CommonError.AUTH_REQUIRED;
    }
    return null;
  }

  private static boolean isPresent() {
    try {
      Class.forName(
          "org.springframework.security.access.AccessDeniedException",
          false,
          SecurityErrors.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
