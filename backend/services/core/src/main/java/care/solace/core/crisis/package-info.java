/**
 * Crisis: the crisis queue (admin screen 3), the on-call rota and escalation (D11, D15, D39).
 * Alerts come from the safety monitor's ai.risk.flagged; responders see only the flagged text.
 */
@ApplicationModule(displayName = "Crisis")
package care.solace.core.crisis;

import org.springframework.modulith.ApplicationModule;
