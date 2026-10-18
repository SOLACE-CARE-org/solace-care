package care.solace.core.messaging;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging settings ({@code solace.notifications.*}, D38), beside the delivery settings.
 *
 * @param linkBase guest SMS links: this plus a short guest token, about 31 characters
 * @param quietStart default start of quiet hours ([21:00])
 * @param quietEnd default end of quiet hours ([07:00])
 * @param defaultTimeZone when nothing better is known
 */
@ConfigurationProperties("solace.notifications")
record MessagingSettings(
    @DefaultValue("https://slc.cm/g/") String linkBase,
    @DefaultValue("21:00") LocalTime quietStart,
    @DefaultValue("07:00") LocalTime quietEnd,
    @DefaultValue("Africa/Douala") String defaultTimeZone) {}
