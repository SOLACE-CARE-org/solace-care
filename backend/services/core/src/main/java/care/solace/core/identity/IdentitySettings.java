package care.solace.core.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Identity settings ({@code solace.identity.*}).
 *
 * @param membersIssuer the members realm (D23); only its tokens belong to member accounts
 * @param termsVersion the Terms version a new seeker must accept; the legal text itself is managed
 *     in Content (admin portal v1), which will own this value
 * @param privacyNoticeVersion likewise for the privacy notice
 * @param unverifiedSignupTtl how long an account waits for its first sign-in before it is removed.
 *     Not in the specs: a setting
 * @param keycloak where Core manages member accounts in Keycloak
 */
@ConfigurationProperties("solace.identity")
record IdentitySettings(
    @DefaultValue("http://localhost:8180/realms/solace-members") String membersIssuer,
    @DefaultValue("v1") String termsVersion,
    @DefaultValue("v1") String privacyNoticeVersion,
    @DefaultValue("PT24H") Duration unverifiedSignupTtl,
    @DefaultValue Keycloak keycloak) {

  /**
   * @param adminUrl Keycloak's base URL for its admin API
   * @param realm the members realm's name
   * @param clientRegistration the Spring Security client registration of the realm's {@code
   *     core-admin} client (manage-users only)
   */
  record Keycloak(
      @DefaultValue("http://localhost:8180") String adminUrl,
      @DefaultValue("solace-members") String realm,
      @DefaultValue("keycloak-members-admin") String clientRegistration) {}
}
