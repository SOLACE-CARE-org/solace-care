package care.solace.core.sessions;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Session settings ({@code solace.sessions.*}). Join opening 10 minutes before the start, tokens
 * lasting 15 minutes past the end and the 2-minute reconnection are decisions (D18, System Design)
 * and live in {@link Sessions} as constants.
 *
 * @param url the media server apps connect to (wss)
 * @param apiUrl the media server's API, for creating rooms
 * @param apiKey LiveKit API key; tokens and webhooks are signed with its secret
 * @param apiSecret LiveKit API secret, from Vault outside local runs
 * @param noShowAfter after the start, a session only one person joined is a no-show ([15], D36)
 * @param completeAfter after the end, a session still running is completed ([15], D36)
 */
@ConfigurationProperties("solace.sessions")
record SessionSettings(
    @DefaultValue("ws://localhost:7880") String url,
    @DefaultValue("http://localhost:7880") URI apiUrl,
    @DefaultValue("devkey") String apiKey,
    @DefaultValue("solace-local-livekit-secret-0123456789") String apiSecret,
    @DefaultValue("PT15M") Duration noShowAfter,
    @DefaultValue("PT15M") Duration completeAfter) {}
