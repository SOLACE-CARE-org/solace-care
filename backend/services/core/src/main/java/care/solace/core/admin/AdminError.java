package care.solace.core.admin;

import care.solace.errors.ErrorCode;

/**
 * Errors of settings, flags and the team; titles in messages.properties and messages_fr.properties.
 */
enum AdminError implements ErrorCode {
  /** A fixed decision, or a value the identity service holds. */
  SETTING_FIXED("admin.setting-fixed", 409),
  /** Not a number in the setting's range, or not HH:MM. */
  SETTING_INVALID("admin.setting-invalid", 400),
  /** The safety monitor, for example. */
  FLAG_LOCKED("admin.flag-locked", 409),
  /** Someone already has a staff account with this email. */
  STAFF_EMAIL_TAKEN("admin.staff-email-taken", 409),
  /** Super admins cannot remove their own Super admin role or deactivate themselves. */
  STAFF_SELF("admin.staff-self", 409),
  /** The account is deactivated. */
  STAFF_DEACTIVATED("admin.staff-deactivated", 409);

  private final String code;
  private final int status;

  AdminError(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String area() {
    return "ADM";
  }
}
