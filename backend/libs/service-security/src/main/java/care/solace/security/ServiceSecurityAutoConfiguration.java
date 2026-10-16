package care.solace.security;

import care.solace.errors.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Every service is a resource server (CLAUDE.md, Sign-in: "each validates the token again behind
 * the gateway").
 *
 * <ul>
 *   <li>REST: tokens from either realm in {@code solace.security.issuers}, stateless, health checks
 *       public, everything else authenticated; 401 and 403 as problem responses.
 *   <li>gRPC: only client-credentials tokens from {@code solace.security.service-issuer} whose
 *       audience names this service, and each method needs the scope declared in {@link
 *       GrpcMethodScopes}. Methods not declared are refused.
 * </ul>
 *
 * Realm keys are fetched on first use, so a service starts even while Keycloak is still starting.
 */
@AutoConfiguration(
    before = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
@EnableConfigurationProperties(SecuritySettings.class)
public class ServiceSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  KeycloakAuthorities keycloakAuthorities() {
    return new KeycloakAuthorities();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class Rest {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain solaceRestSecurity(
        HttpSecurity http,
        SecuritySettings settings,
        KeycloakAuthorities authorities,
        Problems problems,
        LocaleResolver localeResolver,
        ObjectMapper json)
        throws Exception {
      ProblemSecurityHandlers handlers =
          new ProblemSecurityHandlers(problems, localeResolver, json);
      JwtIssuerAuthenticationManagerResolver issuers =
          new JwtIssuerAuthenticationManagerResolver(
              trustedIssuers(settings.issuers(), authorities));
      return http.csrf(csrf -> csrf.disable())
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers(settings.publicPaths().toArray(String[]::new))
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .oauth2ResourceServer(
              oauth ->
                  oauth
                      .authenticationManagerResolver(issuers)
                      .authenticationEntryPoint(handlers)
                      .accessDeniedHandler(handlers))
          .exceptionHandling(
              e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
          .build();
    }

    /** One authentication manager per trusted realm, created when its first token arrives. */
    private static org.springframework.security.authentication.AuthenticationManagerResolver<String>
        trustedIssuers(List<String> issuers, KeycloakAuthorities authorities) {
      Set<String> trusted = Set.copyOf(issuers);
      Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();
      return issuer -> {
        if (!trusted.contains(issuer)) {
          return null;
        }
        return managers.computeIfAbsent(
            issuer,
            iss -> {
              JwtAuthenticationProvider provider =
                  new JwtAuthenticationProvider(
                      lazyDecoder(iss, JwtValidators.createDefaultWithIssuer(iss)));
              provider.setJwtAuthenticationConverter(authorities);
              return provider::authenticate;
            });
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(GrpcSecurity.class)
  @ConditionalOnBean(GrpcMethodScopes.class)
  static class Grpc {

    /** Decodes service tokens: staff realm, and the audience must name this service. */
    @Bean
    @ConditionalOnMissingBean(name = "solaceServiceJwtDecoder")
    JwtDecoder solaceServiceJwtDecoder(SecuritySettings settings) {
      if (settings.audience() == null || settings.audience().isBlank()) {
        throw new IllegalStateException(
            "solace.security.audience must name this service for its gRPC server");
      }
      OAuth2TokenValidator<Jwt> validator =
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(settings.serviceIssuer()),
              new JwtClaimValidator<List<String>>(
                  "aud", aud -> aud != null && aud.contains(settings.audience())));
      return lazyDecoder(settings.serviceIssuer(), validator);
    }

    @Bean
    @GlobalServerInterceptor
    @ConditionalOnMissingBean
    AuthenticationProcessInterceptor solaceGrpcSecurity(
        GrpcSecurity grpc,
        GrpcMethodScopes scopes,
        JwtDecoder solaceServiceJwtDecoder,
        KeycloakAuthorities authorities)
        throws Exception {
      return grpc.authorizeRequests(
              requests -> {
                requests.methods("grpc.health.v1.Health/*").permitAll();
                scopes
                    .required()
                    .forEach(
                        (method, scope) -> requests.methods(method).hasAuthority("SCOPE_" + scope));
                requests.allRequests().denyAll();
              })
          .oauth2ResourceServer(
              oauth ->
                  oauth.jwt(
                      jwt ->
                          jwt.decoder(solaceServiceJwtDecoder)
                              .jwtAuthenticationConverter(authorities)))
          .build();
    }
  }

  static JwtDecoder lazyDecoder(String issuer, OAuth2TokenValidator<Jwt> validator) {
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
          decoder.setJwtValidator(validator);
          return decoder;
        });
  }
}
