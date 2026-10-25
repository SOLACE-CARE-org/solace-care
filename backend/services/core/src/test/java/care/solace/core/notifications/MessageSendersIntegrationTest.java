package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SMS and email senders as rows (D61), with a local server standing in for an SMS gateway: the
 * message goes to the first active sender with its key, the next one takes over when it fails, and
 * no channel is ever left without an active sender.
 */
class MessageSendersIntegrationTest extends SpecialistFlowTest {

  private static final Path KEYS = keys();
  private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
  private static final AtomicInteger ANSWER = new AtomicInteger(200);
  private static final HttpServer GATEWAY = gateway();

  @Autowired MessageSenders senders;

  private final RequestPostProcessor superAdmin = staff("sa-m", "M. Ndi", "SUPER_ADMIN");
  private final RequestPostProcessor ops = staff("ops-m", "O", "OPERATIONS");

  @DynamicPropertySource
  static void keysDir(DynamicPropertyRegistry properties) {
    properties.add("solace.notifications.secrets-dir", KEYS::toString);
  }

  private static Path keys() {
    try {
      Path dir = Files.createTempDirectory("sender-keys");
      Files.writeString(dir.resolve("test-gateway.api-key"), "k-123\n");
      return dir;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer gateway() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/send",
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RECEIVED.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            exchange.sendResponseHeaders(ANSWER.get(), -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stop() {
    GATEWAY.stop(0);
  }

  @Test
  void anSmsGoesThroughTheGatewayAndTheLogTakesOverWhenItFails() throws Exception {
    String code = "gw-" + System.nanoTime() % 100000;
    String settings =
        """
        {"url":"http://127.0.0.1:%d/send","format":"JSON",
         "fields":{"to":"{to}","message":"{text}","sender":"{from}"},"from":"Solace"}
        """
            .formatted(GATEWAY.getAddress().getPort());
    mvc.perform(
            post("/admin/v1/message-senders")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"%s","channel":"SMS","adapter":"HTTP_SMS","displayName":"Gateway",
                     "priority":1,"secretRef":"test-gateway","settings":%s,"reason":"Test"}
                    """
                        .formatted(code, settings)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.keysFound").value(true));
    try {
      change(code, 1, "ACTIVE", settings).andExpect(status().isOk());

      RECEIVED.clear();
      senders.send("+237670000001", "Solace: hello");
      assertThat(RECEIVED).hasSize(1);
      assertThat(RECEIVED.get(0))
          .startsWith("Bearer k-123 ")
          .contains("\"to\":\"+237670000001\"")
          .contains("\"message\":\"Solace: hello\"")
          .contains("\"sender\":\"Solace\"");

      // The gateway fails: the log sender, next by priority, takes the message.
      ANSWER.set(500);
      senders.send("+237670000001", "Solace: hello again");
      assertThat(RECEIVED).hasSize(2);
      ANSWER.set(200);

      mvc.perform(
              post("/admin/v1/message-senders/" + code + "/test")
                  .with(superAdmin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"to\":\"+237670000002\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.ok").value(true));
      assertThat(RECEIVED.get(RECEIVED.size() - 1)).contains("test message from the admin portal");
    } finally {
      change(code, 2, "DISABLED", settings);
    }
  }

  @Test
  void onlyASuperAdminChangesSendersAndAChannelKeepsOneActive() throws Exception {
    mvc.perform(get("/admin/v1/message-senders").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.code == 'log-sms')].status").value("ACTIVE"));
    mvc.perform(
            post("/admin/v1/message-senders")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"x-sms","channel":"SMS","adapter":"HTTP_SMS","displayName":"X",
                     "priority":2,"secretRef":"x","settings":{},"reason":"Test"}
                    """))
        .andExpect(status().isForbidden());
    // A plain-http gateway elsewhere, or an adapter for the other channel, is refused.
    mvc.perform(
            post("/admin/v1/message-senders")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"x-sms","channel":"SMS","adapter":"HTTP_SMS","displayName":"X",
                     "priority":2,"secretRef":"x","settings":{"url":"http://sms.example.com/send",
                     "fields":{"to":"{to}","text":"{text}"}},"reason":"Test"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("message-sender.invalid"));
    mvc.perform(
            post("/admin/v1/message-senders")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"x-sms","channel":"SMS","adapter":"SMTP","displayName":"X",
                     "priority":2,"secretRef":"x","settings":{"host":"mail","from":"a@b.c"},
                     "reason":"Test"}
                    """))
        .andExpect(status().isBadRequest());

    JsonNode email = sender("log-email");
    mvc.perform(
            put("/admin/v1/message-senders/log-email")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "version", email.path("version").asInt(),
                            "displayName", "Log",
                            "status", "DISABLED",
                            "priority", 1,
                            "secretRef", "none",
                            "settings", Map.of(),
                            "reason", "Test"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("message-sender.last-active"));
  }

  private JsonNode sender(String code) throws Exception {
    for (JsonNode s : json(mvc.perform(get("/admin/v1/message-senders").with(superAdmin)))) {
      if (s.path("code").asText().equals(code)) {
        return s;
      }
    }
    throw new AssertionError("no sender " + code);
  }

  private ResultActions change(String code, int version, String status, String settings)
      throws Exception {
    return mvc.perform(
        put("/admin/v1/message-senders/" + code)
            .with(superAdmin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"version":%d,"displayName":"Gateway","status":"%s","priority":1,
                 "secretRef":"test-gateway","settings":%s,"reason":"Test"}
                """
                    .formatted(version, status, settings)));
  }
}
