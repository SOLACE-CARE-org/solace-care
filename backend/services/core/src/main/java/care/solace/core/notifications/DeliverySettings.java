package care.solace.core.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push delivery settings ({@code solace.notifications.*}, D38). {@code sender} (SMS and email) is
 * read by the senders themselves.
 *
 * @param pushSender {@code expo}, or {@code logging} for local runs and tests
 * @param expoUrl Expo's push service
 * @param expoAccessToken optional; Expo's enhanced security, from Vault
 */
@ConfigurationProperties("solace.notifications")
record DeliverySettings(
    @DefaultValue("expo") String pushSender,
    @DefaultValue("https://exp.host") String expoUrl,
    @DefaultValue("") String expoAccessToken) {}
