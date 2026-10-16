package care.solace.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token settings for a service ({@code solace.security.*}).
 *
 * @param issuers Keycloak realms whose tokens the REST API accepts: members and staff (D23)
 * @param serviceIssuer the realm that issues client-credentials tokens for service-to-service calls
 * @param audience this service's name; gRPC tokens must name it in {@code aud} (e.g. {@code core})
 * @param publicPaths REST paths open without a token: health checks only
 */
@ConfigurationProperties("solace.security")
public record SecuritySettings(
    @DefaultValue({
          "http://localhost:8180/realms/solace-members",
          "http://localhost:8180/realms/solace-staff"
        })
        List<String> issuers,
    @DefaultValue("http://localhost:8180/realms/solace-staff") String serviceIssuer,
    String audience,
    @DefaultValue({"/actuator/health", "/actuator/health/**", "/actuator/info"})
        List<String> publicPaths) {}
