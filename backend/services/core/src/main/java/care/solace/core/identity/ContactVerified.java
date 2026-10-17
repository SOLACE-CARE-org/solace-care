package care.solace.core.identity;

import java.util.UUID;

/**
 * Published inside Core when a member's first sign-in proves their phone number or email. The guest
 * module listens and moves guest bookings made with the same contact into the account (D6).
 *
 * <p>An in-process event: it carries the contact because Guest access needs it, so it is never
 * externalised to Kafka.
 */
public record ContactVerified(UUID accountId, String phone, String email) {}
