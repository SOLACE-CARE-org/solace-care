package care.solace.ai.admin;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.gateway.ModelRegistry;
import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI models, routes and budgets for staff (admin screen 26, AI controls; D61). Super admins change
 * them, each change with a reason and audited; Operations and Clinical reviewers look. A changed
 * route waits as pending until the feature's evaluation set passes on it with the live prompt
 * version, and only then goes live. Keys are never read or shown, only whether they are found.
 */
@RestController
class AdminModelsController {

  private static final String AREA = "aiControls";
  private static final String NAME = "^[a-z][a-z0-9-]{1,40}$";
  private static final String SECRET_REF = "^[a-z0-9][a-z0-9._-]{0,62}$";
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final StaffGate gate;
  private final ModelRegistry registry;
  private final ModelGateway gateway;
  private final Features features;
  private final Evaluations evaluations;
  private final JdbcClient db;
  private final TransactionTemplate transactions;
  private final AuditLog audit;
  private final Clock clock;

  AdminModelsController(
      StaffGate gate,
      ModelRegistry registry,
      ModelGateway gateway,
      Features features,
      Evaluations evaluations,
      JdbcClient db,
      TransactionTemplate transactions,
      AuditLog audit,
      Clock clock) {
    this.gate = gate;
    this.registry = registry;
    this.gateway = gateway;
    this.features = features;
    this.evaluations = evaluations;
    this.db = db;
    this.transactions = transactions;
    this.audit = audit;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ModelView(
      String name,
      ModelRegistry.Kind kind,
      String url,
      String model,
      boolean local,
      String secretRef,
      ModelRegistry.Status status,
      int priority,
      int version,
      String updatedBy,
      Instant updatedAt,
      boolean keyFound) {}

  record ModelDraft(
      @NotBlank @Pattern(regexp = NAME) String name,
      @NotNull ModelRegistry.Kind kind,
      @NotBlank @Size(max = 200) String url,
      @NotBlank @Size(max = 120) String model,
      boolean local,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      @Min(1) @Max(10) int priority,
      @NotBlank @Size(max = 500) String reason) {}

  record ModelChange(
      @Min(1) int version,
      @NotBlank @Size(max = 200) String url,
      @NotBlank @Size(max = 120) String model,
      boolean local,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      @NotNull ModelRegistry.Status status,
      @Min(1) @Max(10) int priority,
      @NotBlank @Size(max = 500) String reason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RouteView(
      Feature feature,
      List<String> live,
      List<String> pending,
      String proposedBy,
      Instant proposedAt,
      Boolean pendingPassed,
      Long monthlyBudget) {}

  record RouteChange(
      @NotNull @Size(max = 10) List<@Pattern(regexp = NAME) String> models,
      @NotBlank @Size(max = 500) String reason) {}

  record Reason(@NotBlank @Size(max = 500) String reason) {}

  record Budget(@Min(0) long tokens, @NotBlank @Size(max = 500) String reason) {}

  // --- Models -----------------------------------------------------------------------------------

  @GetMapping("/admin/v1/ai-models")
  List<ModelView> models(@AuthenticationPrincipal Jwt token) {
    gate.require(token, AREA, false);
    return registry.rows().stream().map(this::view).toList();
  }

  @PostMapping("/admin/v1/ai-models")
  @ResponseStatus(HttpStatus.CREATED)
  ModelView create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ModelDraft body) {
    var staff = gate.require(token, AREA, true);
    address(body.url(), body.local());
    Instant now = clock.instant();
    try {
      transactions.executeWithoutResult(
          s -> {
            db.sql(
                    """
                    INSERT INTO ai_model (name, kind, url, model, local, secret_ref, status,
                                          priority, version, updated_by, updated_by_name,
                                          updated_at)
                    VALUES (:n, :k, :u, :m, :l, :r, 'DRAFT', :p, 1, :by, :name, :now)
                    """)
                .param("n", body.name())
                .param("k", body.kind().name())
                .param("u", body.url().strip())
                .param("m", body.model().strip())
                .param("l", body.local())
                .param("r", body.secretRef())
                .param("p", body.priority())
                .param("by", staff.id())
                .param("name", staff.name())
                .param("now", Timestamp.from(now))
                .update();
            version(body.name(), 1, body.reason(), staff, now);
          });
    } catch (DuplicateKeyException e) {
      throw new SolaceException(ModelError.EXISTS);
    }
    audit.adminAction(
        "ai.model.create",
        "ai_model",
        body.name(),
        body.reason().strip(),
        Map.of("kind", body.kind().name(), "local", String.valueOf(body.local())));
    return view(row(body.name()));
  }

  @PutMapping("/admin/v1/ai-models/{name}")
  ModelView change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String name,
      @Valid @RequestBody ModelChange body) {
    var staff = gate.require(token, AREA, true);
    var current = row(name);
    if (current.version() != body.version()) {
      throw new SolaceException(ModelError.CHANGED);
    }
    address(body.url(), body.local());
    var next =
        new ModelRegistry.Row(
            name,
            current.kind(),
            body.url().strip(),
            body.model().strip(),
            body.local(),
            body.secretRef(),
            body.status(),
            body.priority(),
            current.version(),
            staff.name(),
            clock.instant());
    if (next.status() == ModelRegistry.Status.ACTIVE && !next.local() && !registry.keyFound(next)) {
      throw new SolaceException(ModelError.NOT_READY);
    }
    if (next.status() != ModelRegistry.Status.ACTIVE && inRoute(name)) {
      throw new SolaceException(ModelError.IN_ROUTE);
    }
    Instant now = clock.instant();
    boolean saved =
        Boolean.TRUE.equals(
            transactions.execute(
                s -> {
                  int done =
                      db.sql(
                              """
                              UPDATE ai_model SET url = :u, model = :m, local = :l,
                                     secret_ref = :r, status = :s, priority = :p,
                                     version = version + 1, updated_by = :by,
                                     updated_by_name = :name, updated_at = :now
                               WHERE name = :n AND version = :v
                              """)
                          .param("n", name)
                          .param("u", next.url())
                          .param("m", next.model())
                          .param("l", next.local())
                          .param("r", next.secretRef())
                          .param("s", next.status().name())
                          .param("p", next.priority())
                          .param("v", current.version())
                          .param("by", staff.id())
                          .param("name", staff.name())
                          .param("now", Timestamp.from(now))
                          .update();
                  if (done != 1) {
                    return false;
                  }
                  version(name, current.version() + 1, body.reason(), staff, now);
                  return true;
                }));
    if (!saved) {
      throw new SolaceException(ModelError.CHANGED);
    }
    registry.reload();
    audit.adminAction(
        "ai.model.change",
        "ai_model",
        name,
        body.reason().strip(),
        Map.of("status", next.status().name(), "version", String.valueOf(current.version() + 1)));
    return view(row(name));
  }

  // --- Routes and budgets -----------------------------------------------------------------------

  @GetMapping("/admin/v1/ai-routes")
  List<RouteView> routes(@AuthenticationPrincipal Jwt token) {
    gate.require(token, AREA, false);
    var live = registry.routes("LIVE");
    var pending = registry.routes("PENDING");
    List<RouteView> out = new ArrayList<>();
    for (Feature f : Feature.values()) {
      var proposal =
          db.sql("SELECT proposed_by_name, proposed_at FROM ai_route_proposal WHERE feature = :f")
              .param("f", f.name())
              .query(
                  (rs, n) ->
                      Map.entry(
                          rs.getString("proposed_by_name"),
                          rs.getTimestamp("proposed_at").toInstant()))
              .optional();
      Boolean passed =
          proposal.isEmpty()
              ? null
              : features
                  .live(f)
                  .flatMap(
                      v -> evaluations.latestOnRoute(v.id(), pending.getOrDefault(f, List.of())))
                  .map(Evaluations.Run::passed)
                  .orElse(false);
      Long budget =
          db.sql("SELECT monthly_budget FROM ai_feature WHERE code = :f")
              .param("f", f.name())
              .query((rs, n) -> (Long) rs.getObject("monthly_budget"))
              .optional()
              .orElse(null);
      out.add(
          new RouteView(
              f,
              live.getOrDefault(f, List.of()),
              proposal.isPresent() ? pending.getOrDefault(f, List.of()) : null,
              proposal.map(Map.Entry::getKey).orElse(null),
              proposal.map(Map.Entry::getValue).orElse(null),
              passed,
              budget));
    }
    return out;
  }

  /** Saves a pending route for the feature; it goes live only after its evaluation passes. */
  @PutMapping("/admin/v1/ai-routes/{feature}")
  RouteView propose(
      @AuthenticationPrincipal Jwt token,
      @PathVariable Feature feature,
      @Valid @RequestBody RouteChange body) {
    var staff = gate.require(token, AREA, true);
    var active =
        registry.rows().stream()
            .filter(
                r ->
                    r.kind() == ModelRegistry.Kind.CHAT
                        && r.status() == ModelRegistry.Status.ACTIVE)
            .map(ModelRegistry.Row::name)
            .toList();
    if (!active.containsAll(body.models())
        || new HashSet<>(body.models()).size() != body.models().size()) {
      throw new SolaceException(ModelError.ROUTE_INVALID);
    }
    Instant now = clock.instant();
    transactions.executeWithoutResult(
        s -> {
          db.sql("DELETE FROM ai_route WHERE feature = :f AND state = 'PENDING'")
              .param("f", feature.name())
              .update();
          int priority = 1;
          for (String model : body.models()) {
            db.sql(
                    """
                    INSERT INTO ai_route (feature, state, priority, model_name)
                    VALUES (:f, 'PENDING', :p, :m)
                    """)
                .param("f", feature.name())
                .param("p", priority++)
                .param("m", model)
                .update();
          }
          db.sql(
                  """
                  INSERT INTO ai_route_proposal (feature, reason, proposed_by, proposed_by_name,
                                                 proposed_at)
                  VALUES (:f, :r, :by, :name, :now)
                  ON CONFLICT (feature) DO UPDATE
                     SET reason = EXCLUDED.reason, proposed_by = EXCLUDED.proposed_by,
                         proposed_by_name = EXCLUDED.proposed_by_name,
                         proposed_at = EXCLUDED.proposed_at
                  """)
              .param("f", feature.name())
              .param("r", body.reason().strip())
              .param("by", staff.id())
              .param("name", staff.name())
              .param("now", Timestamp.from(now))
              .update();
        });
    audit.adminAction(
        "ai.route.propose",
        "ai_feature",
        feature.name(),
        body.reason().strip(),
        Map.of("models", String.valueOf(body.models().size())));
    return route(token, feature);
  }

  /** Runs the feature's evaluation set with its live version on the pending route. */
  @PostMapping("/admin/v1/ai-routes/{feature}/evaluation")
  Evaluations.Run evaluate(@AuthenticationPrincipal Jwt token, @PathVariable Feature feature) {
    var staff = gate.require(token, AREA, true);
    var pending = pending(feature);
    var live =
        features.live(feature).orElseThrow(() -> new SolaceException(ModelError.NO_LIVE_VERSION));
    var run = evaluations.evaluateOnRoute(live, pending, staff.name(), gateway);
    audit.adminAction(
        "ai.route.evaluate",
        "ai_feature",
        feature.name(),
        Map.of("passed", String.valueOf(run.passed())));
    return run;
  }

  /** The pending route goes live, once its evaluation passed; an empty route needs none. */
  @PostMapping("/admin/v1/ai-routes/{feature}/promotion")
  RouteView promote(
      @AuthenticationPrincipal Jwt token,
      @PathVariable Feature feature,
      @Valid @RequestBody Reason body) {
    var staff = gate.require(token, AREA, true);
    var pending = pending(feature);
    String runId = null;
    if (!pending.isEmpty()) {
      var run =
          features
              .live(feature)
              .flatMap(v -> evaluations.latestOnRoute(v.id(), pending))
              .filter(Evaluations.Run::passed)
              .orElseThrow(() -> new SolaceException(ModelError.NOT_EVALUATED));
      runId = run.id().toString();
    }
    Instant now = clock.instant();
    String evalRun = runId;
    transactions.executeWithoutResult(
        s -> {
          db.sql("DELETE FROM ai_route WHERE feature = :f AND state = 'LIVE'")
              .param("f", feature.name())
              .update();
          db.sql("UPDATE ai_route SET state = 'LIVE' WHERE feature = :f AND state = 'PENDING'")
              .param("f", feature.name())
              .update();
          db.sql("DELETE FROM ai_route_proposal WHERE feature = :f")
              .param("f", feature.name())
              .update();
          int version =
              db.sql(
                      "SELECT coalesce(max(version), 0) + 1 FROM ai_route_version WHERE feature = :f")
                  .param("f", feature.name())
                  .query(Integer.class)
                  .single();
          db.sql(
                  """
                  INSERT INTO ai_route_version (feature, version, routes, eval_run_id, reason,
                                                changed_by, changed_by_name, changed_at)
                  VALUES (:f, :v, :r, CAST(:e AS uuid), :reason, :by, :name, :now)
                  """)
              .param("f", feature.name())
              .param("v", version)
              .param("r", String.join(",", pending))
              .param("e", evalRun)
              .param("reason", body.reason().strip())
              .param("by", staff.id())
              .param("name", staff.name())
              .param("now", Timestamp.from(now))
              .update();
        });
    registry.reload();
    audit.adminAction(
        "ai.route.promote",
        "ai_feature",
        feature.name(),
        body.reason().strip(),
        Map.of("models", String.valueOf(pending.size())));
    return route(token, feature);
  }

  @DeleteMapping("/admin/v1/ai-routes/{feature}/pending")
  RouteView discard(@AuthenticationPrincipal Jwt token, @PathVariable Feature feature) {
    gate.require(token, AREA, true);
    pending(feature);
    transactions.executeWithoutResult(
        s -> {
          db.sql("DELETE FROM ai_route WHERE feature = :f AND state = 'PENDING'")
              .param("f", feature.name())
              .update();
          db.sql("DELETE FROM ai_route_proposal WHERE feature = :f")
              .param("f", feature.name())
              .update();
        });
    audit.adminAction("ai.route.discard", "ai_feature", feature.name(), Map.of());
    return route(token, feature);
  }

  @PutMapping("/admin/v1/ai-features/{feature}/budget")
  RouteView budget(
      @AuthenticationPrincipal Jwt token,
      @PathVariable Feature feature,
      @Valid @RequestBody Budget body) {
    gate.require(token, AREA, true);
    db.sql("UPDATE ai_feature SET monthly_budget = :b WHERE code = :f")
        .param("b", body.tokens())
        .param("f", feature.name())
        .update();
    registry.reload();
    audit.adminAction(
        "ai.budget.change",
        "ai_feature",
        feature.name(),
        body.reason().strip(),
        Map.of("tokens", String.valueOf(body.tokens())));
    return route(token, feature);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private RouteView route(Jwt token, Feature feature) {
    return routes(token).stream().filter(r -> r.feature() == feature).findFirst().orElseThrow();
  }

  private List<String> pending(Feature feature) {
    boolean proposed =
        db.sql("SELECT count(*) FROM ai_route_proposal WHERE feature = :f")
                .param("f", feature.name())
                .query(Integer.class)
                .single()
            > 0;
    if (!proposed) {
      throw new SolaceException(ModelError.NO_PENDING);
    }
    return registry.routes("PENDING").getOrDefault(feature, List.of());
  }

  private boolean inRoute(String name) {
    return db.sql("SELECT count(*) FROM ai_route WHERE model_name = :n")
            .param("n", name)
            .query(Integer.class)
            .single()
        > 0;
  }

  private ModelRegistry.Row row(String name) {
    return registry.rows().stream()
        .filter(r -> r.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /** https, or http for a model Solace hosts inside its own network. */
  private static void address(String url, boolean local) {
    try {
      URI uri = URI.create(url.strip());
      boolean ok =
          uri.getHost() != null
              && ("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())));
      if (!ok) {
        throw new SolaceException(ModelError.INVALID);
      }
    } catch (IllegalArgumentException e) {
      throw new SolaceException(ModelError.INVALID);
    }
  }

  private void version(String name, int version, String reason, StaffGate.Staff by, Instant now) {
    var r = row(name);
    db.sql(
            """
            INSERT INTO ai_model_version (name, version, snapshot, reason, changed_by,
                                          changed_by_name, changed_at)
            VALUES (:n, :v, CAST(:j AS jsonb), :r, :by, :name, :now)
            """)
        .param("n", name)
        .param("v", version)
        .param("j", snapshot(r))
        .param("r", reason.strip())
        .param("by", by.id())
        .param("name", by.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  private static String snapshot(ModelRegistry.Row r) {
    try {
      return JSON.writeValueAsString(
          Map.of(
              "kind", r.kind().name(),
              "url", r.url(),
              "model", r.model(),
              "local", r.local(),
              "status", r.status().name(),
              "priority", r.priority(),
              "secretRef", r.secretRef()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private ModelView view(ModelRegistry.Row r) {
    return new ModelView(
        r.name(),
        r.kind(),
        r.url(),
        r.model(),
        r.local(),
        r.secretRef(),
        r.status(),
        r.priority(),
        r.version(),
        r.updatedByName(),
        r.updatedAt(),
        r.local() || registry.keyFound(r));
  }
}
