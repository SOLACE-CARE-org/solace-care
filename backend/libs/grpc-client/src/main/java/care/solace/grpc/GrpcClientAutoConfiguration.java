package care.solace.grpc;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Every gRPC client in a service gets the 2-second deadline and, when {@code
 * solace.grpc.client-registration} names a client-credentials registration, the service's token.
 * {@link ResilientCalls} wraps calls in a breaker per callee.
 */
@AutoConfiguration
@EnableConfigurationProperties(BreakerSettings.class)
public class GrpcClientAutoConfiguration {

  @Bean
  @GlobalClientInterceptor
  @ConditionalOnMissingBean
  DeadlineInterceptor solaceDeadlineInterceptor() {
    return new DeadlineInterceptor();
  }

  @Bean
  @ConditionalOnMissingBean
  CircuitBreakerRegistry solaceGrpcBreakers(BreakerSettings settings) {
    return CircuitBreakerRegistry.of(ResilientCalls.breakerConfig(settings));
  }

  @Bean
  @ConditionalOnMissingBean
  ResilientCalls resilientCalls(CircuitBreakerRegistry breakers) {
    return new ResilientCalls(breakers);
  }

  /**
   * Fetches and caches the service's client-credentials token from Keycloak, using the Spring
   * Security client registration named by {@code solace.grpc.client-registration}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(OAuth2AuthorizedClientManager.class)
  @ConditionalOnProperty("solace.grpc.client-registration")
  static class ServiceToken {

    @Bean
    @ConditionalOnMissingBean
    OAuth2AuthorizedClientManager solaceServiceClientManager(
        ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
      return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    }

    @Bean
    @GlobalClientInterceptor
    BearerTokenInterceptor solaceBearerTokenInterceptor(
        OAuth2AuthorizedClientManager manager,
        org.springframework.core.env.Environment environment) {
      String registration = environment.getRequiredProperty("solace.grpc.client-registration");
      return new BearerTokenInterceptor(
          () -> {
            OAuth2AuthorizedClient client =
                manager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(registration)
                        .principal(registration)
                        .build());
            if (client == null) {
              throw new IllegalStateException("No token for client registration " + registration);
            }
            return client.getAccessToken().getTokenValue();
          });
    }
  }
}
