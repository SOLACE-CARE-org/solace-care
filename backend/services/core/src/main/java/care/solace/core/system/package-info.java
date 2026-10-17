/**
 * System: platform plumbing with no user data, such as the Phase 0 end-to-end ping.
 *
 * <p>Not one of the product modules in the System Design; it holds what belongs to no product
 * module.
 */
@ApplicationModule(displayName = "System")
package care.solace.core.system;

import org.springframework.modulith.ApplicationModule;
