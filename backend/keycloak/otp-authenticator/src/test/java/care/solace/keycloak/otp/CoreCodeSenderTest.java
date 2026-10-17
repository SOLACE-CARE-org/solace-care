package care.solace.keycloak.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sender against a stand-in for Keycloak's token endpoint and Core's internal endpoint. */
class CoreCodeSenderTest {

  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final AtomicInteger tokensIssued = new AtomicInteger();
  private volatile int coreStatus = 202;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertThat(form)
              .contains("grant_type=client_credentials")
              .contains("client_id=keycloak-code-sender")
              .contains("client_secret=s3cret");
          byte[] answer =
              ("{\"access_token\":\"token-"
                      + tokensIssued.incrementAndGet()
                      + "\",\"expires_in\":300}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/sign-in-codes",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(coreStatus, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private CoreCodeSender sender() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new CoreCodeSender(
        base + "/", base + "/token", "s3cret", HttpClient.newHttpClient(), Clock.systemUTC());
  }

  @Test
  void handsTheCodeToCoreWithTheServiceToken() {
    CoreCodeSender sender = sender();
    sender.send(Contact.parse("+237670000101"), "482913", "fr", Duration.ofMinutes(10));
    sender.send(Contact.parse("dr.mbarga@example.cm"), "112233", null, Duration.ofSeconds(90));

    assertThat(bodies.get(0))
        .isEqualTo(
            "{\"channel\":\"SMS\",\"to\":\"+237670000101\",\"code\":\"482913\","
                + "\"expiresInMinutes\":10,\"locale\":\"fr\"}");
    assertThat(bodies.get(1))
        .isEqualTo(
            "{\"channel\":\"EMAIL\",\"to\":\"dr.mbarga@example.cm\",\"code\":\"112233\","
                + "\"expiresInMinutes\":1}");
    assertThat(authorizations).containsOnly("Bearer token-1");
    assertThat(tokensIssued).hasValue(1);
  }

  @Test
  void aRefusedTokenIsRenewedOnce() {
    CoreCodeSender sender = sender();
    sender.send(Contact.parse("+237670000101"), "482913", "en", Duration.ofMinutes(10));
    coreStatus = 401;
    sender.send(Contact.parse("+237670000101"), "482914", "en", Duration.ofMinutes(10));

    assertThat(authorizations)
        .containsExactly("Bearer token-1", "Bearer token-1", "Bearer token-2");
  }

  @Test
  void aFailureIsSwallowedSoTheAnswerStaysTheSame() {
    server.stop(0);
    sender().send(Contact.parse("+237670000101"), "482913", "en", Duration.ofMinutes(10));
    assertThat(bodies).isEmpty();
  }

  @Test
  void refusesToStartWithoutASecret() {
    assertThatThrownBy(() -> CoreCodeSender.fromEnvironment(Map.<String, String>of()::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SOLACE_CODE_SENDER_SECRET");
  }
}
