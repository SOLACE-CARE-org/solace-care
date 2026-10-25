package care.solace.core.notifications;

import care.solace.jobs.Job;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The SMS and email senders in force (D61): one client per ACTIVE row, each behind its own circuit
 * breaker. A message goes to the ACTIVE sender of its channel with the lowest priority number, and
 * to the next one when it fails; it fails only when every sender failed. Rows are re-read on the
 * settings refresh, so a change on the portal is in force within a minute.
 *
 * <p>Keys come from files mounted from Vault in {@code solace.notifications.secrets-dir}: {@code
 * <secret_ref>.api-key} for an SMS gateway, {@code .username} and {@code .password} for a mail
 * server. A LOGGING sender, which writes messages with their codes to the log, is used only where
 * {@code solace.notifications.sender=logging}: local runs and tests.
 */
@Component
class MessageSenders implements SmsSender, EmailSender, InitializingBean {

  private static final Logger LOG = LoggerFactory.getLogger(MessageSenders.class);

  /** Every sender of the channel failed, or none is active; {@code reason} carries no data. */
  static final class SendFailed extends RuntimeException {
    SendFailed(String sender, String reason) {
      super("Sender " + sender + " failed: " + reason);
    }
  }

  private final SenderStore store;
  private final RestClient.Builder restClients;
  private final Clock clock;
  private final Path secretsDir;
  private final boolean loggingAllowed;

  private record Client(
      SenderStore.Sender row, Map<String, String> keys, Object adapter, CircuitBreaker breaker) {}

  private volatile List<Client> clients = List.of();

  MessageSenders(
      SenderStore store,
      RestClient.Builder restClients,
      Clock clock,
      @Value("${solace.notifications.secrets-dir:/etc/solace/message-senders}") String secretsDir,
      @Value("${solace.notifications.sender:}") String sender) {
    this.store = store;
    this.restClients = restClients;
    this.clock = clock;
    this.secretsDir = Path.of(secretsDir);
    this.loggingAllowed = "logging".equals(sender);
  }

  @Override
  public void afterPropertiesSet() {
    bootstrap();
    reload();
  }

  /** Local runs and tests start with LOGGING senders for both channels when no sender exists. */
  void bootstrap() {
    if (!loggingAllowed || !store.all().isEmpty()) {
      return;
    }
    var system = new SenderStore.By("system", "System");
    for (var channel : SenderStore.Channel.values()) {
      try {
        store.insert(
            new SenderStore.Sender(
                "log-" + channel.name().toLowerCase(),
                channel,
                SenderStore.Adapter.LOGGING,
                "Log (local only)",
                SenderStore.Status.ACTIVE,
                1,
                "none",
                Map.of(),
                1,
                system.name(),
                clock.instant()),
            "Local runs: solace.notifications.sender=logging",
            system,
            clock.instant());
      } catch (DuplicateKeyException e) {
        // Another instance set it up at the same moment.
      }
    }
  }

  @Job(
      name = "core.notifications.senders-reload",
      description = "Reloads the SMS and email senders",
      every = "${solace.admin.settings-refresh:PT30S}",
      initialDelay = "${solace.admin.settings-refresh:PT30S}",
      caution = true)
  void reload() {
    try {
      Map<String, Client> current = new HashMap<>();
      clients.forEach(c -> current.put(c.row().code(), c));
      List<Client> next = new ArrayList<>();
      for (var row : store.all()) {
        if (row.status() != SenderStore.Status.ACTIVE) {
          continue;
        }
        if (row.adapter() == SenderStore.Adapter.LOGGING && !loggingAllowed) {
          LOG.warn("Sender {} writes to the log and is skipped here", row.code());
          continue;
        }
        var keys = keys(row);
        var was = current.get(row.code());
        if (was != null && was.row().version() == row.version() && was.keys().equals(keys)) {
          next.add(new Client(row, keys, was.adapter(), was.breaker()));
          continue;
        }
        try {
          next.add(new Client(row, keys, adapter(row, keys), breaker(row.code())));
        } catch (IllegalArgumentException e) {
          LOG.warn("Sender {} has invalid settings and is skipped: {}", row.code(), e.getMessage());
        }
      }
      clients = List.copyOf(next);
    } catch (RuntimeException e) {
      LOG.warn("Reloading message senders failed; the last ones loaded stay in force", e);
    }
  }

  @Override
  public void send(String phone, String text) {
    deliver(SenderStore.Channel.SMS, adapter -> ((SmsSender) adapter).send(phone, text));
  }

  @Override
  public void send(String address, String subject, String body) {
    deliver(
        SenderStore.Channel.EMAIL, adapter -> ((EmailSender) adapter).send(address, subject, body));
  }

  private void deliver(SenderStore.Channel channel, java.util.function.Consumer<Object> send) {
    String last = "no active sender";
    for (var c : clients) {
      if (c.row().channel() != channel) {
        continue;
      }
      try {
        c.breaker().executeRunnable(() -> send.accept(c.adapter()));
        return;
      } catch (CallNotPermittedException e) {
        last = c.row().code() + " circuit open";
      } catch (SendFailed e) {
        last = e.getMessage();
        LOG.warn("{}; trying the next sender", e.getMessage());
      }
    }
    throw new SendFailed(channel.name(), last);
  }

  // --- For the portal ---------------------------------------------------------------------------

  /** Whether every key the adapter needs is found. Never the keys themselves. */
  boolean keysFound(SenderStore.Sender row) {
    var keys = keys(row);
    return switch (row.adapter()) {
      case HTTP_SMS -> !keys.getOrDefault("api-key", "").isBlank();
      case SMTP -> true;
      case LOGGING -> true;
    };
  }

  Optional<String> circuit(String code) {
    return clients.stream()
        .filter(c -> c.row().code().equals(code))
        .findFirst()
        .map(c -> c.breaker().getState().name());
  }

  /** Checks the row's settings by building its adapter; empty when they are valid. */
  Optional<String> problem(SenderStore.Sender row) {
    if (row.adapter() == SenderStore.Adapter.LOGGING && !loggingAllowed) {
      return Optional.of("A LOGGING sender is for local runs only");
    }
    try {
      adapter(row, keys(row));
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.of(e.getMessage());
    }
  }

  /** Sends one message through this row only, as it is now, without the circuit or fallback. */
  void test(SenderStore.Sender row, String to, String subject, String text) {
    var adapter = adapter(row, keys(row));
    if (row.channel() == SenderStore.Channel.SMS) {
      ((SmsSender) adapter).send(to, text);
    } else {
      ((EmailSender) adapter).send(to, subject, text);
    }
  }

  private Object adapter(SenderStore.Sender row, Map<String, String> keys) {
    return switch (row.adapter()) {
      case HTTP_SMS ->
          new HttpSmsSender(
              row.code(),
              HttpSmsSender.Settings.of(row.settings()),
              keys.get("api-key"),
              restClients);
      case SMTP ->
          new SmtpEmailSender(
              row.code(),
              SmtpEmailSender.Settings.of(row.settings()),
              keys.get("username"),
              keys.get("password"));
      case LOGGING -> new LoggingMessageSender();
    };
  }

  private Map<String, String> keys(SenderStore.Sender row) {
    Map<String, String> keys = new HashMap<>();
    for (String key : List.of("api-key", "username", "password")) {
      Path file = secretsDir.resolve(row.secretRef() + "." + key);
      if (Files.isRegularFile(file)) {
        try {
          keys.put(key, Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return Map.copyOf(keys);
  }

  private static CircuitBreaker breaker(String code) {
    return CircuitBreaker.of(
        "sender-" + code,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .recordExceptions(SendFailed.class)
            .build());
  }
}
