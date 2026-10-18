package care.solace.core.guests;

import java.util.UUID;

/**
 * Published inside Core when a member's first sign-in proves a guest's phone: the guest's bookings
 * now belong to the account (D34). In-process only.
 */
public record GuestContactClaimed(UUID guestContactId, UUID accountId) {}
