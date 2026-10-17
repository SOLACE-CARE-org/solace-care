package care.solace.gateway;

import care.solace.errors.CommonError;
import care.solace.errors.Problems;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * The edge check (System Design: "clients hit one API gateway that validates the JWT"). A request
 * needs a valid token from a trusted realm unless its path is public; the token is passed on
 * unchanged and checked again by the service.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfiguration {

  @Bean
  Problems problems(MessageSource messages) {
    // No tracing yet: trace ids are added with the end-to-end slice.
    return new Problems(messages, () -> null);
  }

  /** Builds the token decoder for a trusted realm, fetching its keys on first use. */
  @Bean
  Function<String, ReactiveJwtDecoder> issuerDecoders() {
    return issuer -> {
      NimbusReactiveJwtDecoder decoder =
          NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
      return decoder;
    };
  }

  @Bean
  SecurityWebFilterChain edgeSecurity(
      ServerHttpSecurity http,
      GatewaySettings settings,
      Function<String, ReactiveJwtDecoder> issuerDecoders,
      ProblemWriter problems) {
    JwtIssuerReactiveAuthenticationManagerResolver issuers =
        new JwtIssuerReactiveAuthenticationManagerResolver(
            trusted(settings.issuers(), issuerDecoders));
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .cors(cors -> cors.configurationSource(cors(settings.corsOrigins())))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers(HttpMethod.OPTIONS)
                    .permitAll()
                    .pathMatchers(settings.publicPaths().toArray(String[]::new))
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationManagerResolver(issuers)
                    .authenticationEntryPoint(
                        (exchange, e) -> {
                          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                          return problems.write(exchange, CommonError.AUTH_REQUIRED);
                        })
                    .accessDeniedHandler(
                        (exchange, e) -> problems.write(exchange, CommonError.ACCESS_DENIED)))
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(
                    (exchange, e) -> {
                      exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                      return problems.write(exchange, CommonError.AUTH_REQUIRED);
                    }))
        .build();
  }

  private static ReactiveAuthenticationManagerResolver<String> trusted(
      List<String> issuers, Function<String, ReactiveJwtDecoder> decoders) {
    Set<String> trusted = Set.copyOf(issuers);
    Map<String, ReactiveAuthenticationManager> managers = new ConcurrentHashMap<>();
    return issuer -> {
      if (!trusted.contains(issuer)) {
        return Mono.empty();
      }
      return Mono.just(
          managers.computeIfAbsent(
              issuer, iss -> new JwtReactiveAuthenticationManager(decoders.apply(iss))));
    };
  }

  private static CorsConfigurationSource cors(List<String> origins) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
