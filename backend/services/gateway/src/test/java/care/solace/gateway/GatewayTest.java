package care.solace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The gateway against a real Redis and stand-in services, with tokens signed by a test key in place
 * of Keycloak's, so signature and issuer checks run for real.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Each request costs 60 tokens and one comes back per second: three requests, then one a
      // minute, so the test cannot race the refill.
      "solace.gateway.rate-limit.replenish-rate=1",
      "solace.gateway.rate-limit.burst-capacity=180",
      "solace.gateway.rate-limit.requested-tokens=60"
    })
@Testcontainers
class GatewayTest {

  static final String MEMBERS = "http://localhost:8180/realms/solace-members";
  static final String STAFF = "http://localhost:8180/realms/solace-staff";

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);

  static final KeyPair KEYS = rsaKeys();
  static final Map<String, HttpServer> SERVICES =
      Map.of(
          "core", echoServer("core"), "tracking", echoServer("tracking"), "ai", echoServer("ai"));

  @Autowired WebTestClient web;

  @DynamicPropertySource
  static void services(DynamicPropertyRegistry registry) {
    SERVICES.forEach(
        (name, server) ->
            registry.add(
                "solace.gateway.services." + name,
                () -> "http://localhost:" + server.getAddress().getPort()));
  }

  @AfterAll
  static void stopServices() {
    SERVICES.values().forEach(server -> server.stop(0));
    RESTARTED.values().forEach(server -> server.stop(0));
  }

  @Test
  void healthIsOpen() {
    web.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }

  @Test
  void noTokenIsAProblemInTheCallersLanguage() {
    web.get()
        .uri("/api/core/v1/system/ping")
        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CM")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("auth.required")
        .jsonPath("$.title")
        .isEqualTo("Une connexion est nécessaire.");
  }

  @Test
  void memberTokenIsRoutedToCoreWithPrefixRemovedAndTokenPassedOn() {
    String token = token(MEMBERS, "seeker-route");

    web.get()
        .uri("/api/core/v1/system/ping?x=1")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.service")
        .isEqualTo("core")
        .jsonPath("$.path")
        .isEqualTo("/v1/system/ping")
        .jsonPath("$.authorization")
        .isEqualTo("Bearer " + token);
  }

  @Test
  void staffTokenReachesTrackingAndAi() {
    String token = token(STAFF, "staff-route");

    for (String service : List.of("tracking", "ai")) {
      web.get()
          .uri("/api/" + service + "/v1/x")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.service")
          .isEqualTo(service)
          .jsonPath("$.path")
          .isEqualTo("/v1/x");
    }
  }

  @Test
  void tokenFromAnotherIssuerIsRefused() {
    web.get()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("https://evil.example/realms/x", "s"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void tamperedTokenIsRefused() {
    String token = token(MEMBERS, "seeker-tamper");
    String tampered = token.substring(0, token.length() - 4) + "AAAA";

    web.get()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void eachPersonIsRateLimitedOnTheirOwn() {
    String busy = token(MEMBERS, "seeker-busy");
    HttpStatus last = HttpStatus.OK;
    for (int i = 0; i < 6 && last != HttpStatus.TOO_MANY_REQUESTS; i++) {
      last =
          HttpStatus.valueOf(
              web.get()
                  .uri("/api/core/v1/x")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + busy)
                  .exchange()
                  .returnResult(String.class)
                  .getStatus()
                  .value());
    }
    assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    web.get()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + busy)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("rate-limited")
        .jsonPath("$.reference")
        .isEqualTo("REQ-429");

    web.get()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(MEMBERS, "seeker-calm"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void serviceThatIsDownIsAnUnavailableProblem() {
    HttpServer ai = SERVICES.get("ai");
    ai.stop(0);
    try {
      web.get()
          .uri("/api/ai/v1/x")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(STAFF, "staff-down"))
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("dependency.unavailable")
          .jsonPath("$.reference")
          .isEqualTo("SYS-503");
    } finally {
      restart(ai);
    }
  }

  @Test
  void adminPortalOriginMayCallFromTheBrowser() {
    web.options()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");

    web.options()
        .uri("/api/core/v1/x")
        .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  /** Keycloak stand-in: tokens signed with the test key, issuer checked as in production. */
  @TestConfiguration
  static class TestKeys {
    @Bean
    @Primary
    Function<String, ReactiveJwtDecoder> testIssuerDecoders() {
      return issuer -> {
        NimbusReactiveJwtDecoder decoder =
            NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
      };
    }
  }

  static String token(String issuer, String subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(subject)
              .issueTime(new Date())
              .expirationTime(Date.from(Instant.now().plusSeconds(300)))
              .claim("realm_access", Map.of("roles", List.of("SEEKER")))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(KEYS.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair rsaKeys() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Brings a stopped stand-in back on the same port, for the tests that run after. */
  private static void restart(HttpServer stopped) {
    int port = stopped.getAddress().getPort();
    SERVICES.entrySet().stream()
        .filter(e -> e.getValue() == stopped)
        .findFirst()
        .ifPresent(e -> RESTARTED.put(e.getKey(), echoServer(e.getKey(), port)));
  }

  static final Map<String, HttpServer> RESTARTED = new java.util.concurrent.ConcurrentHashMap<>();

  private static HttpServer echoServer(String name) {
    return echoServer(name, 0);
  }

  /** A stand-in service answering with its name and the path and token it received. */
  private static HttpServer echoServer(String name, int port) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
      server.createContext(
          "/",
          exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body =
                "{\"service\":\""
                    + name
                    + "\",\"path\":\""
                    + exchange.getRequestURI().getPath()
                    + "\",\"authorization\":\""
                    + (auth == null ? "" : auth)
                    + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
