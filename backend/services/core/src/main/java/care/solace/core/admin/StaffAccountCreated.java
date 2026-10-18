package care.solace.core.admin;

/**
 * A Super admin added a staff member (D7, D47). Notifications sends the "Staff account created"
 * email; the email never contains the password.
 */
public record StaffAccountCreated(String email) {}
