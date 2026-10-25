package care.solace.ai.gateway;

import care.solace.jobs.Job;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI models and routes as rows (D61). Each ACTIVE model becomes a route the gateway can call; a
 * feature's LIVE route is the order its chat models are tried in. Keys come from files mounted from
 * Vault in {@code solace.ai.secrets-dir} ({@code <secret_ref>.api-key}); the ref {@code config}
 * takes the key of the same-named model in {@code solace.ai.models}. Before any model row exists,
 * the models, routes and budgets in configuration become the first rows, so nothing changes until a
 * Super admin changes it.
 */
@Component
public class ModelRegistry implements InitializingBean {

  private static final Logger LOG = LoggerFactory.getLogger(ModelRegistry.class);

  public enum Kind {
    CHAT,
    SPEECH,
    EMBEDDING
  }

  public enum Status {
    DRAFT,
    ACTIVE,
    DISABLED
  }

  public record Row(
      String name,
      Kind kind,
      String url,
      String model,
      boolean local,
      String secretRef,
      Status status,
      int priority,
      int version,
      String updatedByName,
      Instant updatedAt) {}

  private record Loaded(
      List<ModelRoute> chat,
      List<SpeechRoute> speech,
      List<EmbeddingRoute> embeddings,
      Map<Feature, List<String>> routes,
      Map<Feature, Long> budgets) {}

  private final JdbcClient db;
  private final GatewaySettings settings;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Path secretsDir;
  private volatile Loaded loaded = new Loaded(List.of(), List.of(), List.of(), Map.of(), Map.of());
  private Map<String, String> builtFrom = Map.of();

  ModelRegistry(
      JdbcClient db,
      GatewaySettings settings,
      TransactionTemplate transactions,
      Clock clock,
      @Value("${solace.ai.secrets-dir:/etc/solace/ai-models}") String secretsDir) {
    this.db = db;
    this.settings = settings;
    this.transactions = transactions;
    this.clock = clock;
    this.secretsDir = Path.of(secretsDir);
  }

  @Override
  public void afterPropertiesSet() {
    bootstrap();
    reload();
  }

  List<ModelRoute> chat() {
    return loaded.chat();
  }

  List<SpeechRoute> speech() {
    return loaded.speech();
  }

  List<EmbeddingRoute> embeddings() {
    return loaded.embeddings();
  }

  /** The feature's LIVE route, model names in order. */
  public List<String> route(Feature feature) {
    return loaded.routes().getOrDefault(feature, List.of());
  }

  /** Tokens a month, when a Super admin set one; otherwise the configured budget counts. */
  Optional<Long> budget(Feature feature) {
    return Optional.ofNullable(loaded.budgets().get(feature));
  }

  // --- Rows -------------------------------------------------------------------------------------

  public List<Row> rows() {
    return db.sql("SELECT * FROM ai_model ORDER BY kind, priority, name")
        .query(
            (rs, n) ->
                new Row(
                    rs.getString("name"),
                    Kind.valueOf(rs.getString("kind")),
                    rs.getString("url"),
                    rs.getString("model"),
                    rs.getBoolean("local"),
                    rs.getString("secret_ref"),
                    Status.valueOf(rs.getString("status")),
                    rs.getInt("priority"),
                    rs.getInt("version"),
                    rs.getString("updated_by_name"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  public boolean keyFound(Row row) {
    return !key(row).isBlank();
  }

  /** The routes of one state for every feature, names in order. */
  public Map<Feature, List<String>> routes(String state) {
    Map<Feature, List<String>> routes = new EnumMap<>(Feature.class);
    db.sql("SELECT feature, model_name FROM ai_route WHERE state = :s ORDER BY feature, priority")
        .param("s", state)
        .query(
            rs -> {
              routes
                  .computeIfAbsent(Feature.valueOf(rs.getString("feature")), f -> new ArrayList<>())
                  .add(rs.getString("model_name"));
            });
    return routes;
  }

  // --- Loading ----------------------------------------------------------------------------------

  /** The configured models, routes and budgets become the first rows (D61). */
  void bootstrap() {
    if (db.sql("SELECT count(*) FROM ai_model").query(Integer.class).single() > 0) {
      return;
    }
    Instant now = clock.instant();
    try {
      transactions.executeWithoutResult(
          s -> {
            int p = 1;
            for (var m : settings.models()) {
              insert(m, Kind.CHAT, 1, now);
            }
            for (var m : settings.speech()) {
              insert(m, Kind.SPEECH, Math.min(p++, 10), now);
            }
            p = 1;
            for (var m : settings.embeddings()) {
              insert(m, Kind.EMBEDDING, Math.min(p++, 10), now);
            }
            var known = settings.models().stream().map(GatewaySettings.Model::name).toList();
            settings
                .routes()
                .forEach(
                    (feature, names) -> {
                      int priority = 1;
                      for (String name : names) {
                        if (!known.contains(name)) {
                          LOG.warn("Route of {} names unknown model {}; left out", feature, name);
                          continue;
                        }
                        db.sql(
                                """
                                INSERT INTO ai_route (feature, state, priority, model_name)
                                VALUES (:f, 'LIVE', :p, :m)
                                """)
                            .param("f", feature.name())
                            .param("p", priority++)
                            .param("m", name)
                            .update();
                      }
                    });
            settings
                .budgets()
                .forEach(
                    (feature, tokens) ->
                        db.sql("UPDATE ai_feature SET monthly_budget = :b WHERE code = :f")
                            .param("b", tokens)
                            .param("f", feature.name())
                            .update());
          });
    } catch (DuplicateKeyException e) {
      LOG.info("AI models were set up by another instance");
    }
  }

  private void insert(GatewaySettings.Model m, Kind kind, int priority, Instant now) {
    db.sql(
            """
            INSERT INTO ai_model (name, kind, url, model, local, secret_ref, status, priority,
                                  version, updated_by, updated_by_name, updated_at)
            VALUES (:n, :k, :u, :m, :l, 'config', 'ACTIVE', :p, 1, 'system', 'System', :now)
            """)
        .param("n", m.name())
        .param("k", kind.name())
        .param("u", m.url())
        .param("m", m.model())
        .param("l", m.local())
        .param("p", priority)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Rebuilds the routes when a row or a key changed. */
  @Job(
      name = "ai.models.reload",
      description = "Reloads the AI models, routes and budgets",
      every = "${solace.ai.models-refresh:PT30S}",
      initialDelay = "${solace.ai.models-refresh:PT30S}",
      caution = true)
  public synchronized void reload() {
    try {
      var rows = rows().stream().filter(r -> r.status() == Status.ACTIVE).toList();
      Map<String, String> fingerprint = new HashMap<>();
      rows.forEach(r -> fingerprint.put(r.name(), r.version() + ":" + key(r).hashCode()));
      var routes = routes("LIVE");
      Map<Feature, Long> budgets = new EnumMap<>(Feature.class);
      db.sql("SELECT code, monthly_budget FROM ai_feature WHERE monthly_budget IS NOT NULL")
          .query(
              rs -> {
                budgets.put(Feature.valueOf(rs.getString("code")), rs.getLong("monthly_budget"));
              });
      Loaded current = loaded;
      if (fingerprint.equals(builtFrom)) {
        loaded =
            new Loaded(current.chat(), current.speech(), current.embeddings(), routes, budgets);
        return;
      }
      List<ModelRoute> chat = new ArrayList<>();
      List<SpeechRoute> speech = new ArrayList<>();
      List<EmbeddingRoute> embeddings = new ArrayList<>();
      for (var r : rows) {
        var model = new GatewaySettings.Model(r.name(), r.url(), r.model(), key(r), r.local());
        switch (r.kind()) {
          case CHAT -> chat.add(new SpringAiRoute(model));
          case SPEECH -> speech.add(new HttpSpeechRoute(model));
          case EMBEDDING -> embeddings.add(new HttpEmbeddingRoute(model));
        }
      }
      loaded =
          new Loaded(
              List.copyOf(chat), List.copyOf(speech), List.copyOf(embeddings), routes, budgets);
      builtFrom = fingerprint;
    } catch (RuntimeException e) {
      LOG.warn("Reloading AI models failed; the last ones loaded stay in force", e);
    }
  }

  private String key(Row row) {
    if ("config".equals(row.secretRef())) {
      return settings.models().stream()
          .filter(m -> m.name().equals(row.name()))
          .findFirst()
          .or(() -> settings.speech().stream().filter(m -> m.name().equals(row.name())).findFirst())
          .or(
              () ->
                  settings.embeddings().stream()
                      .filter(m -> m.name().equals(row.name()))
                      .findFirst())
          .map(GatewaySettings.Model::apiKey)
          .orElse("");
    }
    Path file = secretsDir.resolve(row.secretRef() + ".api-key");
    if (!Files.isRegularFile(file)) {
      return "";
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
