package care.solace.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway settings ({@code solace.gateway.*}).
 *
 * @param services where each service listens: {@code /api/<name>/**} is routed there
 * @param issuers Keycloak realms whose tokens are accepted (D23)
 * @param publicPaths paths open without a token: health, seeker sign-up and the catalogue so far;
 *     guest browsing and booking join them with their epics
 * @param rateLimit requests allowed per person (or per address when signed out)
 * @param corsOrigins web origins allowed to call the API from a browser: the admin portal
 */
@ConfigurationProperties("solace.gateway")
public record GatewaySettings(
    Map<String, URI> services,
    @DefaultValue({
          "http://localhost:8180/realms/solace-members",
          "http://localhost:8180/realms/solace-staff"
        })
        List<String> issuers,
    @DefaultValue({
          "/actuator/health",
          "/actuator/health/**",
          "/api/core/v1/signups",
          "/api/core/v1/specialist-signups",
          "/api/core/v1/slot-holds",
          "/api/core/v1/slot-holds/**",
          "/api/core/v1/guest/**",
          "/api/core/v1/webhooks/**",
          "/api/core/v1/catalogue/**"
        })
        List<String> publicPaths,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue("http://localhost:4200") List<String> corsOrigins) {

  /**
   * Token bucket per caller. Not in the specs, so settings with defaults.
   *
   * @param replenishRate requests per second added back to the bucket
   * @param burstCapacity the most tokens the bucket holds
   * @param requestedTokens tokens one request costs
   */
  public record RateLimit(
      @DefaultValue("20") int replenishRate,
      @DefaultValue("40") int burstCapacity,
      @DefaultValue("1") int requestedTokens) {}
}
