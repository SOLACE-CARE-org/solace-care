package care.solace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What guests read before sign-in passes the edge check. Nothing listens behind the gateway here,
 * so an allowed request ends in a server error; a refused one ends in 401.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "solace.gateway.services.core=http://localhost:1",
      "solace.gateway.services.tracking=http://localhost:1"
    })
@Testcontainers
class GuestPathsTest {

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);

  @Autowired WebTestClient web;

  @Test
  void needNoToken() {
    for (String path :
        List.of(
            "/api/core/v1/config",
            "/api/core/v1/content",
            "/api/core/v1/content/crisis-resources",
            "/api/core/v1/health-check",
            "/api/tracking/v1/tracking/images/card")) {
      web.get()
          .uri(path)
          .exchange()
          .expectStatus()
          .value(status -> assertThat(status).as(path).isGreaterThanOrEqualTo(500));
    }
  }

  @Test
  void everythingElseStillNeedsOne() {
    web.get().uri("/api/core/v1/me").exchange().expectStatus().isUnauthorized();
  }
}
