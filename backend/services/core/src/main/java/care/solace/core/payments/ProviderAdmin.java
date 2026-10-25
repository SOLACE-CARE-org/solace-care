package care.solace.core.payments;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Changing payment providers and routes (D61). Only Super admins do this, each change with a
 * reason; the controller checks the role. The rules:
 *
 * <ul>
 *   <li>A new provider starts as DRAFT; its adapter never changes.
 *   <li>ACTIVE needs every key and a passing connection test; a LIVE provider also needs the four
 *       activation checks, kept with the version.
 *   <li>DISABLED needs nothing in flight at the provider. DRAINING is the way there.
 *   <li>Every purpose and method keeps at least one route to an ACTIVE provider, so the routes
 *       always leave a way to collect, refund and pay out.
 * </ul>
 */
@Service
class ProviderAdmin {

  private final ProviderStore store;
  private final PaymentProviders providers;
  private final TransactionTemplate transactions;
  private final Clock clock;

  ProviderAdmin(
      ProviderStore store,
      PaymentProviders providers,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.providers = providers;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** What a Super admin confirms before a LIVE provider becomes ACTIVE. */
  record Checks(
      boolean recordsOfProcessing,
      boolean threatModel,
      boolean contract,
      boolean testTransactions) {

    boolean all() {
      return recordsOfProcessing && threatModel && contract && testTransactions;
    }

    Map<String, Object> asSnapshot() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("recordsOfProcessing", recordsOfProcessing);
      m.put("threatModel", threatModel);
      m.put("contract", contract);
      m.put("testTransactions", testTransactions);
      return Map.of("activationChecks", m);
    }
  }

  record Draft(
      String code,
      ProviderStore.Adapter adapter,
      String displayName,
      ProviderStore.Mode mode,
      String baseUrl,
      String secretRef) {}

  record Change(
      int version,
      String displayName,
      ProviderStore.Mode mode,
      ProviderStore.Status status,
      String baseUrl,
      String secretRef,
      Checks checks) {}

  /** The outcome of a connection test; {@code problem} never carries a key. */
  record Test(boolean ok, boolean keysFound, String problem) {}

  ProviderStore.Provider create(Draft d, String reason, Payments.Decider by) {
    var row =
        new ProviderStore.Provider(
            d.code(),
            d.adapter(),
            d.displayName().strip(),
            d.mode(),
            ProviderStore.Status.DRAFT,
            address(d.baseUrl()),
            d.secretRef(),
            1,
            by.name(),
            clock.instant());
    valid(row);
    try {
      transactions.executeWithoutResult(
          s -> store.insert(row, reason.strip(), Map.of(), by, clock.instant()));
    } catch (DuplicateKeyException e) {
      throw new SolaceException(PaymentError.PROVIDER_EXISTS);
    }
    providers.reload();
    return store.provider(d.code()).orElseThrow();
  }

  ProviderStore.Provider change(String code, Change c, String reason, Payments.Decider by) {
    var current = provider(code);
    if (current.version() != c.version()) {
      throw new SolaceException(PaymentError.PROVIDER_CHANGED);
    }
    var next =
        new ProviderStore.Provider(
            code,
            current.adapter(),
            c.displayName().strip(),
            c.mode(),
            c.status(),
            address(c.baseUrl()),
            c.secretRef(),
            current.version(),
            by.name(),
            clock.instant());
    valid(next);
    Map<String, Object> extra = Map.of();
    if (next.status() == ProviderStore.Status.ACTIVE && activates(current, next)) {
      if (next.mode() == ProviderStore.Mode.LIVE) {
        if (c.checks() == null || !c.checks().all()) {
          throw new SolaceException(PaymentError.PROVIDER_CHECKS);
        }
        extra = c.checks().asSnapshot();
      }
      if (!test(next).ok()) {
        throw new SolaceException(PaymentError.PROVIDER_NOT_READY);
      }
    }
    if (next.status() == ProviderStore.Status.DISABLED
        && current.status() != ProviderStore.Status.DISABLED
        && store.inFlight(code) > 0) {
      throw new SolaceException(PaymentError.PROVIDER_IN_FLIGHT);
    }
    var snapshot = extra;
    boolean done =
        Boolean.TRUE.equals(
            transactions.execute(
                s -> {
                  if (!store.update(
                      next, current.version(), reason.strip(), snapshot, by, clock.instant())) {
                    return false;
                  }
                  // A provider that stops being ACTIVE must not strand a route.
                  if (next.status() != ProviderStore.Status.ACTIVE) {
                    uncovered(store.routes(), statuses(code, next.status()));
                  }
                  return true;
                }));
    if (!done) {
      throw new SolaceException(PaymentError.PROVIDER_CHANGED);
    }
    providers.reload();
    return provider(code);
  }

  /** Builds a client from the row as it is now and makes a call that moves no money. */
  Test test(ProviderStore.Provider row) {
    if (!providers.keysFound(row)) {
      return new Test(false, false, "Keys missing for " + row.secretRef());
    }
    try {
      providers.test(row);
      return new Test(true, true, null);
    } catch (PaymentProvider.Unavailable | IllegalArgumentException e) {
      return new Test(false, true, e.getMessage());
    }
  }

  ProviderStore.Provider provider(String code) {
    return store.provider(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  // --- Routes -----------------------------------------------------------------------------------

  /** Replaces every route; each purpose and method has distinct priorities and providers. */
  List<ProviderStore.Route> replaceRoutes(
      List<ProviderStore.Route> given, int version, String reason, Payments.Decider by) {
    Map<String, ProviderStore.Status> statuses = new LinkedHashMap<>();
    store.providers().forEach(p -> statuses.put(p.code(), p.status()));
    Set<String> seen = new HashSet<>();
    for (var r : given) {
      var status = statuses.get(r.providerCode());
      if (status == null
          || status == ProviderStore.Status.DISABLED
          || !seen.add(r.purpose() + "/" + r.method() + "/" + r.providerCode())
          || !seen.add(r.purpose() + "/" + r.method() + "#" + r.priority())
          || (r.minAmount() != null && r.maxAmount() != null && r.minAmount() > r.maxAmount())) {
        throw new SolaceException(PaymentError.ROUTES_INVALID);
      }
    }
    var routes = new ArrayList<>(given);
    routes.sort(
        Comparator.comparing(ProviderStore.Route::purpose)
            .thenComparing(ProviderStore.Route::method)
            .thenComparingInt(ProviderStore.Route::priority));
    uncovered(routes, statuses);
    boolean done =
        Boolean.TRUE.equals(
            transactions.execute(
                s -> store.replaceRoutes(routes, version, reason.strip(), by, clock.instant())));
    if (!done) {
      throw new SolaceException(PaymentError.ROUTES_CHANGED);
    }
    providers.reload();
    return store.routes();
  }

  /** Refuses routes that leave a purpose and method without an ACTIVE provider. */
  private static void uncovered(
      List<ProviderStore.Route> routes, Map<String, ProviderStore.Status> statuses) {
    for (var purpose : ProviderStore.Purpose.values()) {
      for (var method : PaymentProvider.Method.values()) {
        boolean covered =
            routes.stream()
                .anyMatch(
                    r ->
                        r.purpose() == purpose
                            && r.method() == method
                            && statuses.get(r.providerCode()) == ProviderStore.Status.ACTIVE);
        if (!covered) {
          throw new SolaceException(PaymentError.ROUTES_INVALID);
        }
      }
    }
  }

  private Map<String, ProviderStore.Status> statuses(String code, ProviderStore.Status next) {
    Map<String, ProviderStore.Status> statuses = new LinkedHashMap<>();
    store.providers().forEach(p -> statuses.put(p.code(), p.status()));
    statuses.put(code, next);
    return statuses;
  }

  /** Becomes ACTIVE, or stays ACTIVE with a new mode, address or keys: test it again. */
  private static boolean activates(ProviderStore.Provider before, ProviderStore.Provider after) {
    return before.status() != ProviderStore.Status.ACTIVE
        || before.mode() != after.mode()
        || !java.util.Objects.equals(before.baseUrl(), after.baseUrl())
        || !before.secretRef().equals(after.secretRef());
  }

  private static void valid(ProviderStore.Provider p) {
    if (p.adapter() == ProviderStore.Adapter.SANDBOX && p.mode() == ProviderStore.Mode.LIVE) {
      throw new SolaceException(PaymentError.PROVIDER_INVALID);
    }
  }

  /** Only https addresses; null keeps the adapter's default. */
  private static String address(String baseUrl) {
    return Optional.ofNullable(baseUrl)
        .map(String::strip)
        .filter(u -> !u.isEmpty())
        .map(
            u -> {
              URI uri;
              try {
                uri = URI.create(u);
              } catch (IllegalArgumentException e) {
                throw new SolaceException(PaymentError.PROVIDER_INVALID);
              }
              if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new SolaceException(PaymentError.PROVIDER_INVALID);
              }
              return uri.toString();
            })
        .orElse(null);
  }
}
