/**
 * Guest access: guest profiles keyed by verified phone or email, booking-scoped tokens, claiming on
 * sign-up.
 *
 * <p>Empty in Phase 0; built in Phase 1 and 2 per docs/specs/implementation-plan.md.
 */
@ApplicationModule(displayName = "Guest access")
package care.solace.core.guests;

import org.springframework.modulith.ApplicationModule;
