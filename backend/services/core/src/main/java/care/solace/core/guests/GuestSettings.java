package care.solace.core.guests;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guest settings ({@code solace.guests.*}).
 *
 * @param codeLifetime how long a guest code works ([10] minutes, notification-copy.md)
 * @param codeAttempts wrong tries before the code is gone ([3], StateCodeWrong)
 */
@ConfigurationProperties("solace.guests")
record GuestSettings(
    @DefaultValue("PT10M") Duration codeLifetime, @DefaultValue("3") int codeAttempts) {}
