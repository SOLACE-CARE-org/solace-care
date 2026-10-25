package care.solace.core.payments;

import care.solace.core.admin.FeatureFlags;
import care.solace.jobs.Job;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * The payment providers in force (D61): one client per ACTIVE or DRAINING row, each behind its own
 * circuit breaker, and the routes that say which one takes new work.
 *
 * <ul>
 *   <li>New work (a charge, a refund, a payout) goes to the route's first ACTIVE provider whose
 *       amount limits fit. With {@code paymentRouting} on, a provider whose circuit is open is
 *       skipped, and a charge that certainly never reached a provider is tried at the next one.
 *   <li>Anything already started (checks, webhooks, reconciliation) goes to the provider on the
 *       record, whatever the routes say now.
 *   <li>A refund goes back through the provider that took the payment while it is ACTIVE, otherwise
 *       through the REFUND route for the method the seeker is repaid in.
 *   <li>A transfer is never retried at another provider: it might have been sent.
 * </ul>
 *
 * Rows and routes are re-read on the settings refresh, so a change made on the portal is in force
 * on every instance within a minute.
 */
@Component
class PaymentProviders implements InitializingBean {

  static final String ROUTING_FLAG = "paymentRouting";
  private static final Logger LOG = LoggerFactory.getLogger(PaymentProviders.class);
  private static final URI NOTCHPAY = URI.create("https://api.notchpay.co");

  private final ProviderStore store;
  private final ProviderSecrets secrets;
  private final PaymentSettings settings;
  private final FeatureFlags flags;
  private final RestClient.Builder restClients;
  private final TransactionTemplate transactions;
  private final Clock clock;

  private volatile Map<String, Client> clients = Map.of();
  private volatile List<ProviderStore.Route> routes = List.of();

  PaymentProviders(
      ProviderStore store,
      ProviderSecrets secrets,
      PaymentSettings settings,
      FeatureFlags flags,
      RestClient.Builder restClients,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.secrets = secrets;
    this.settings = settings;
    this.flags = flags;
    this.restClients = restClients;
    this.transactions = transactions;
    this.clock = clock;
  }

  private record Client(
      ProviderStore.Provider row,
      ProviderSecrets.Keys keys,
      PaymentProvider adapter,
      CircuitBreaker breaker) {}

  @Override
  public void afterPropertiesSet() {
    bootstrap();
    reload();
  }

  /**
   * Before any route exists, the provider named by {@code solace.payments.provider} becomes the one
   * provider for every purpose and method, as Core worked before providers were rows.
   */
  void bootstrap() {
    if (!store.routes().isEmpty()) {
      return;
    }
    String code = settings.provider() == null ? "" : settings.provider().strip();
    var adapter =
        switch (code) {
          case "notchpay" -> ProviderStore.Adapter.NOTCHPAY;
          case "sandbox" -> ProviderStore.Adapter.SANDBOX;
          default ->
              throw new IllegalStateException(
                  "No payment route exists and solace.payments.provider is not notchpay or"
                      + " sandbox (D35, D61): '"
                      + code
                      + "'");
        };
    var system = new Payments.Decider("system", "System");
    var now = clock.instant();
    try {
      transactions.executeWithoutResult(
          s -> {
            if (store.provider(code).isEmpty()) {
              store.insert(
                  new ProviderStore.Provider(
                      code,
                      adapter,
                      adapter == ProviderStore.Adapter.SANDBOX ? "Sandbox" : "Notch Pay",
                      adapter == ProviderStore.Adapter.SANDBOX
                          ? ProviderStore.Mode.TEST
                          : ProviderStore.Mode.LIVE,
                      ProviderStore.Status.ACTIVE,
                      null,
                      ProviderSecrets.CONFIG,
                      1,
                      system.name(),
                      now),
                  "Set up from solace.payments.provider",
                  Map.of(),
                  system,
                  now);
            }
            List<ProviderStore.Route> first = new ArrayList<>();
            for (var purpose : ProviderStore.Purpose.values()) {
              for (var method : PaymentProvider.Method.values()) {
                first.add(new ProviderStore.Route(purpose, method, 1, code, null, null));
              }
            }
            store.replaceRoutes(first, 0, "Set up from solace.payments.provider", system, now);
          });
    } catch (DuplicateKeyException e) {
      // Another instance set it up at the same moment.
      LOG.info("Payment providers were set up by another instance");
    }
    if (adapter == ProviderStore.Adapter.SANDBOX) {
      LOG.warn("Payments use the sandbox provider: no real money moves");
    }
  }

  /**
   * Re-reads rows and routes. A client is rebuilt only when how it connects changed (address, mode,
   * keys); a new status or name keeps the client and its circuit.
   */
  @Job(
      name = "core.payments.providers-reload",
      description = "Reloads payment providers and routes",
      every = "${solace.admin.settings-refresh:PT30S}",
      initialDelay = "${solace.admin.settings-refresh:PT30S}",
      locked = true)
  void reload() {
    try {
      Map<String, Client> next = new HashMap<>();
      for (var row : store.providers()) {
        if (!row.status().serves()) {
          continue;
        }
        var keys = secrets.keys(row.adapter(), row.secretRef());
        var current = clients.get(row.code());
        if (current != null && sameConnection(current, row, keys)) {
          next.put(row.code(), new Client(row, keys, current.adapter(), current.breaker()));
        } else {
          next.put(row.code(), new Client(row, keys, adapter(row, keys), breaker(row.code())));
        }
      }
      clients = Map.copyOf(next);
      routes = List.copyOf(store.routes());
    } catch (RuntimeException e) {
      LOG.warn("Reloading payment providers failed; the last ones loaded stay in force", e);
    }
  }

  private static boolean sameConnection(
      Client current, ProviderStore.Provider row, ProviderSecrets.Keys keys) {
    var was = current.row();
    return was.adapter() == row.adapter()
        && was.mode() == row.mode()
        && java.util.Objects.equals(was.baseUrl(), row.baseUrl())
        && was.secretRef().equals(row.secretRef())
        && current.keys().equals(keys);
  }

  // --- Lookups ----------------------------------------------------------------------------------

  /** The provider on a record, while it still serves: checks and webhooks go here. */
  Optional<PaymentProvider> serving(String code) {
    return Optional.ofNullable(code).map(clients::get).map(Guarded::new);
  }

  /** Providers to ask for new work, in the order to ask them. */
  List<PaymentProvider> route(
      ProviderStore.Purpose purpose, PaymentProvider.Method method, long amount) {
    boolean routing = flags.enabled(ROUTING_FLAG);
    List<PaymentProvider> order = new ArrayList<>();
    for (var r : routes) {
      if (r.purpose() != purpose || r.method() != method || !r.fits(amount)) {
        continue;
      }
      var client = clients.get(r.providerCode());
      if (client == null || client.row().status() != ProviderStore.Status.ACTIVE) {
        continue;
      }
      if (routing && client.breaker().getState() == CircuitBreaker.State.OPEN) {
        continue;
      }
      order.add(new Guarded(client));
      if (!routing) {
        break;
      }
    }
    return order;
  }

  /** The original provider while it is ACTIVE, else the first on the REFUND route. */
  Optional<PaymentProvider> forRefund(
      String collectedBy, PaymentProvider.Method method, long amount) {
    var original = clients.get(collectedBy);
    if (original != null && original.row().status() == ProviderStore.Status.ACTIVE) {
      return Optional.of(new Guarded(original));
    }
    return route(ProviderStore.Purpose.REFUND, method, amount).stream().findFirst();
  }

  /** Whether charges at this provider may fall back to the next one. */
  boolean fallsBack() {
    return flags.enabled(ROUTING_FLAG);
  }

  // --- For the portal ---------------------------------------------------------------------------

  boolean keysFound(ProviderStore.Provider row) {
    return secrets.keys(row.adapter(), row.secretRef()).complete(row.adapter());
  }

  /** CLOSED, OPEN or HALF_OPEN while the provider serves; empty when it has no client. */
  Optional<String> circuit(String code) {
    return Optional.ofNullable(clients.get(code)).map(c -> c.breaker().getState().name());
  }

  /** A call that moves no money, with a client built from the row as it is now. */
  void test(ProviderStore.Provider row) {
    adapter(row, secrets.keys(row.adapter(), row.secretRef())).test();
  }

  // --- Building clients -------------------------------------------------------------------------

  private PaymentProvider adapter(ProviderStore.Provider row, ProviderSecrets.Keys keys) {
    return switch (row.adapter()) {
      case NOTCHPAY ->
          new NotchPayProvider(
              row.code(),
              row.baseUrl() == null ? NOTCHPAY : URI.create(row.baseUrl()),
              keys,
              restClients);
      case SANDBOX ->
          new SandboxProvider(row.code(), settings.sandbox(), keys.webhookSecret(), clock);
    };
  }

  private static CircuitBreaker breaker(String code) {
    return CircuitBreaker.of(
        "payments-" + code,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordExceptions(PaymentProvider.Unavailable.class)
            .build());
  }

  /** A provider's adapter behind its circuit breaker; an open circuit means "not sent". */
  private static final class Guarded implements PaymentProvider {

    private final Client client;

    Guarded(Client client) {
      this.client = client;
    }

    private <T> T call(Supplier<T> call) {
      try {
        return client.breaker().executeSupplier(call);
      } catch (CallNotPermittedException e) {
        throw new Unavailable(client.row().code() + " circuit open", e, true);
      }
    }

    @Override
    public String name() {
      return client.row().code();
    }

    @Override
    public Started charge(ChargeRequest request) {
      return call(() -> client.adapter().charge(request));
    }

    @Override
    public Outcome chargeStatus(String providerRef) {
      return call(() -> client.adapter().chargeStatus(providerRef));
    }

    @Override
    public Started transfer(TransferRequest request) {
      return call(() -> client.adapter().transfer(request));
    }

    @Override
    public Outcome transferStatus(String providerRef) {
      return call(() -> client.adapter().transferStatus(providerRef));
    }

    @Override
    public boolean verify(HttpHeaders headers, byte[] body) {
      return client.adapter().verify(headers, body);
    }

    @Override
    public Optional<Notification> parse(byte[] body) {
      return client.adapter().parse(body);
    }

    @Override
    public void test() {
      call(
          () -> {
            client.adapter().test();
            return null;
          });
    }
  }
}
