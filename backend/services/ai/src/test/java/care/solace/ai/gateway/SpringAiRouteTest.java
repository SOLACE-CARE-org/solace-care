package care.solace.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A chat model's url ends with "/v1", as speech and embedding urls do (D61). */
class SpringAiRouteTest {

  private final List<String> paths = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void callsChatCompletionsUnderTheUrlGiven() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          byte[] body =
              """
              {"id":"c1","object":"chat.completion","created":1,"model":"m",
               "choices":[{"index":0,"finish_reason":"stop",
                           "message":{"role":"assistant","content":" Hello "}}],
               "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

    var reply =
        new SpringAiRoute(new GatewaySettings.Model("gateway", url, "m", "key", false))
            .call("system", "user");

    assertThat(paths).containsExactly("/v1/chat/completions");
    assertThat(reply.text()).isEqualTo("Hello");
    assertThat(reply.tokensIn()).isEqualTo(5);
    assertThat(reply.tokensOut()).isEqualTo(2);
  }
}
