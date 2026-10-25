package care.solace.core.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payment providers and their routes (D61), each change kept as a version with its reason. */
@Repository
class ProviderStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The adapters written in code; a provider row picks one. */
  enum Adapter {
    NOTCHPAY,
    SANDBOX
  }

  enum Mode {
    TEST,
    LIVE
  }

  /**
   * DRAFT: set up, no traffic. ACTIVE: takes new work where a route names it. DRAINING: no new
   * work, still checked and still receives webhooks until nothing is in flight. DISABLED: nothing.
   */
  enum Status {
    DRAFT,
    ACTIVE,
    DRAINING,
    DISABLED;

    /** Still answers for what it already holds: checks, webhooks, reconciliation. */
    boolean serves() {
      return this == ACTIVE || this == DRAINING;
    }
  }

  enum Purpose {
    COLLECT,
    REFUND,
    PAYOUT
  }

  record Provider(
      String code,
      Adapter adapter,
      String displayName,
      Mode mode,
      Status status,
      String baseUrl,
      String secretRef,
      int version,
      String updatedByName,
      Instant updatedAt) {

    Provider with(Status next) {
      return new Provider(
          code,
          adapter,
          displayName,
          mode,
          next,
          baseUrl,
          secretRef,
          version,
          updatedByName,
          updatedAt);
    }
  }

  /** One step of a route; amounts in whole FCFA, null for no limit. */
  record Route(
      Purpose purpose,
      PaymentProvider.Method method,
      int priority,
      String providerCode,
      Long minAmount,
      Long maxAmount) {

    boolean fits(long amount) {
      return (minAmount == null || amount >= minAmount)
          && (maxAmount == null || amount <= maxAmount);
    }
  }

  record Version(int version, Map<String, Object> snapshot, String reason, String by, Instant at) {}

  private final JdbcClient db;

  ProviderStore(JdbcClient db) {
    this.db = db;
  }

  List<Provider> providers() {
    return db.sql("SELECT * FROM payment_provider ORDER BY code")
        .query(ProviderStore::provider)
        .list();
  }

  Optional<Provider> provider(String code) {
    return db.sql("SELECT * FROM payment_provider WHERE code = :c")
        .param("c", code)
        .query(ProviderStore::provider)
        .optional();
  }

  /** A new provider at version 1. */
  void insert(
      Provider p, String reason, Map<String, Object> extra, Payments.Decider by, Instant now) {
    db.sql(
            """
            INSERT INTO payment_provider (code, adapter, display_name, mode, status, base_url,
                                          secret_ref, version, updated_by, updated_by_name,
                                          updated_at)
            VALUES (:c, :a, :n, :m, :s, :u, :r, 1, :by, :byName, :now)
            """)
        .param("c", p.code())
        .param("a", p.adapter().name())
        .param("n", p.displayName())
        .param("m", p.mode().name())
        .param("s", p.status().name())
        .param("u", p.baseUrl())
        .param("r", p.secretRef())
        .param("by", by.id())
        .param("byName", by.name())
        .param("now", Timestamp.from(now))
        .update();
    version(p.code(), 1, p, reason, extra, by, now);
  }

  /** Writes {@code next} over version {@code from}; false when someone changed it meanwhile. */
  boolean update(
      Provider next,
      int from,
      String reason,
      Map<String, Object> extra,
      Payments.Decider by,
      Instant now) {
    boolean done =
        db.sql(
                    """
                    UPDATE payment_provider
                       SET display_name = :n, mode = :m, status = :s, base_url = :u,
                           secret_ref = :r, version = version + 1, updated_by = :by,
                           updated_by_name = :byName, updated_at = :now
                     WHERE code = :c AND version = :v
                    """)
                .param("c", next.code())
                .param("n", next.displayName())
                .param("m", next.mode().name())
                .param("s", next.status().name())
                .param("u", next.baseUrl())
                .param("r", next.secretRef())
                .param("v", from)
                .param("by", by.id())
                .param("byName", by.name())
                .param("now", Timestamp.from(now))
                .update()
            == 1;
    if (done) {
      version(next.code(), from + 1, next, reason, extra, by, now);
    }
    return done;
  }

  List<Version> versions(String code) {
    return db.sql(
            """
            SELECT version, snapshot, reason, changed_by_name, changed_at
              FROM payment_provider_version WHERE code = :c ORDER BY version DESC
            """)
        .param("c", code)
        .query(
            (rs, n) ->
                new Version(
                    rs.getInt("version"),
                    read(rs.getString("snapshot")),
                    rs.getString("reason"),
                    rs.getString("changed_by_name"),
                    rs.getTimestamp("changed_at").toInstant()))
        .list();
  }

  // --- Routes -----------------------------------------------------------------------------------

  List<Route> routes() {
    return db.sql("SELECT * FROM payment_route ORDER BY purpose, method, priority")
        .query(
            (rs, n) ->
                new Route(
                    Purpose.valueOf(rs.getString("purpose")),
                    PaymentProvider.Method.valueOf(rs.getString("method")),
                    rs.getInt("priority"),
                    rs.getString("provider_code"),
                    (Long) rs.getObject("min_amount"),
                    (Long) rs.getObject("max_amount")))
        .list();
  }

  int routeVersion() {
    return db.sql("SELECT coalesce(max(version), 0) FROM payment_route_version")
        .query(Integer.class)
        .single();
  }

  /**
   * Replaces every route at once, if the routes are still at version {@code from}; call inside a
   * transaction.
   */
  boolean replaceRoutes(
      List<Route> routes, int from, String reason, Payments.Decider by, Instant now) {
    // One route change at a time: an aggregate cannot be locked FOR UPDATE.
    db.sql("SELECT pg_advisory_xact_lock(hashtext('payment_route'))").query().singleRow();
    if (routeVersion() != from) {
      return false;
    }
    db.sql("DELETE FROM payment_route").update();
    for (Route r : routes) {
      db.sql(
              """
              INSERT INTO payment_route (purpose, method, priority, provider_code, min_amount,
                                         max_amount)
              VALUES (:p, :m, :o, :c, :lo, :hi)
              """)
          .param("p", r.purpose().name())
          .param("m", r.method().name())
          .param("o", r.priority())
          .param("c", r.providerCode())
          .param("lo", r.minAmount())
          .param("hi", r.maxAmount())
          .update();
    }
    db.sql(
            """
            INSERT INTO payment_route_version (version, routes, reason, changed_by,
                                               changed_by_name, changed_at)
            VALUES (:v, CAST(:j AS jsonb), :r, :by, :byName, :now)
            """)
        .param("v", from + 1)
        .param("j", write(routes))
        .param("r", reason)
        .param("by", by.id())
        .param("byName", by.name())
        .param("now", Timestamp.from(now))
        .update();
    return true;
  }

  // --- What a provider still holds ---------------------------------------------------------------

  /** Payments pending, refunds sent and payouts sent at {@code code}: what draining waits for. */
  int inFlight(String code) {
    return db.sql(
            """
            SELECT (SELECT count(*) FROM payment WHERE provider = :c AND status = 'PENDING')
                 + (SELECT count(*) FROM refund WHERE provider = :c AND status = 'SENT')
                 + (SELECT count(*) FROM payout_item WHERE provider = :c AND status = 'SENT')
            """)
        .param("c", code)
        .query(Integer.class)
        .single();
  }

  /** Money the ledger says is at {@code code}: debits less credits on its clearing lines. */
  long clearing(String code) {
    return db.sql(
            """
            SELECT coalesce(sum(debit) - sum(credit), 0) FROM ledger_line
             WHERE account = 'PROVIDER_CLEARING' AND provider = :c
            """)
        .param("c", code)
        .query(Long.class)
        .single();
  }

  private void version(
      String code,
      int version,
      Provider p,
      String reason,
      Map<String, Object> extra,
      Payments.Decider by,
      Instant now) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("adapter", p.adapter().name());
    snapshot.put("displayName", p.displayName());
    snapshot.put("mode", p.mode().name());
    snapshot.put("status", p.status().name());
    snapshot.put("baseUrl", p.baseUrl());
    snapshot.put("secretRef", p.secretRef());
    snapshot.putAll(extra);
    db.sql(
            """
            INSERT INTO payment_provider_version (code, version, snapshot, reason, changed_by,
                                                  changed_by_name, changed_at)
            VALUES (:c, :v, CAST(:j AS jsonb), :r, :by, :byName, :now)
            """)
        .param("c", code)
        .param("v", version)
        .param("j", write(snapshot))
        .param("r", reason)
        .param("by", by.id())
        .param("byName", by.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  private static Provider provider(ResultSet rs, int n) throws SQLException {
    return new Provider(
        rs.getString("code"),
        Adapter.valueOf(rs.getString("adapter")),
        rs.getString("display_name"),
        Mode.valueOf(rs.getString("mode")),
        Status.valueOf(rs.getString("status")),
        rs.getString("base_url"),
        rs.getString("secret_ref"),
        rs.getInt("version"),
        rs.getString("updated_by_name"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(String json) {
    try {
      return JSON.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
