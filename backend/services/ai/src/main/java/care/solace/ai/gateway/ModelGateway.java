package care.solace.ai.gateway;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The one door to every model (System Design, AI service, Model gateway; D56): routes a feature to
 * its models in order, falls back when one fails, stops at the feature's monthly budget, redacts
 * contact details, keeps health data on local models until a transfer is authorised, and logs every
 * call. Feature code never calls a vendor directly.
 */
@Service
public class ModelGateway {

  private static final Logger LOG = LoggerFactory.getLogger(ModelGateway.class);

  /** What the caller asks for. */
  public record Request(
      Feature feature,
      UUID subjectId,
      String promptVersion,
      String system,
      String user,
      boolean healthData) {}

  /** What came back, and from which model. */
  public record Answer(String text, String route) {}

  private final GatewaySettings settings;
  private final Map<String, ModelRoute> routes;
  private final JdbcClient db;
  private final Clock clock;

  ModelGateway(GatewaySettings settings, RouteCatalog catalog, JdbcClient db, Clock clock) {
    this.settings = settings;
    this.routes = new LinkedHashMap<>();
    catalog.routes().forEach(r -> routes.put(r.name(), r));
    this.db = db;
    this.clock = clock;
  }

  /** The model names a feature uses, for the AI controls screen. */
  public List<String> routeNames(Feature feature) {
    return settings.routes().getOrDefault(feature, List.of());
  }

  public Optional<Answer> complete(Request request) {
    Instant now = clock.instant();
    if (usedThisMonth(request.feature(), now) >= settings.budget(request.feature())) {
      log(request, "budget", "BUDGET", 0, 0, 0, null, null, now);
      return Optional.empty();
    }
    String user = Redactor.redact(request.user());
    for (String name : routeNames(request.feature())) {
      ModelRoute route = routes.get(name);
      if (route == null) {
        continue;
      }
      if (request.healthData() && !route.local() && !settings.transferAuthorised()) {
        // Health data stays in Solace's hosting until the transfer is authorised.
        continue;
      }
      long started = System.nanoTime();
      try {
        var reply = route.call(request.system(), user);
        int millis = (int) ((System.nanoTime() - started) / 1_000_000);
        if (reply.text().isBlank()) {
          log(request, name, "FALLBACK", 0, 0, millis, user, null, now);
          continue;
        }
        log(
            request,
            name,
            "OK",
            reply.tokensIn(),
            reply.tokensOut(),
            millis,
            user,
            reply.text(),
            now);
        return Optional.of(new Answer(reply.text(), name));
      } catch (RuntimeException e) {
        int millis = (int) ((System.nanoTime() - started) / 1_000_000);
        // Neither the text nor the model's answer goes to the logs.
        LOG.warn("Model route {} failed for {}; trying the next", name, request.feature());
        log(request, name, "FALLBACK", 0, 0, millis, user, null, now);
      }
    }
    log(request, "none", "FAILED", 0, 0, 0, user, null, now);
    return Optional.empty();
  }

  /** A guardrail refused the answer: recorded against the call, so evaluation can see it. */
  public void refused(Request request, String route, String output) {
    log(
        request,
        route,
        "BLOCKED_GUARDRAIL",
        0,
        0,
        0,
        Redactor.redact(request.user()),
        output,
        clock.instant());
  }

  public void off(Request request) {
    log(request, "none", "OFF", 0, 0, 0, null, null, clock.instant());
  }

  long usedThisMonth(Feature feature, Instant now) {
    Instant month =
        now.atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
    Long used =
        db.sql(
                "SELECT coalesce(sum(tokens_in + tokens_out), 0) FROM ai_call WHERE feature = :f AND created_at >= :m")
            .param("f", feature.name())
            .param("m", Timestamp.from(month))
            .query(Long.class)
            .single();
    return used == null ? 0 : used;
  }

  /** Prompts and outputs are kept [30] days for the safety audit, then deleted. */
  @Scheduled(cron = "0 30 2 * * *", zone = "Africa/Douala")
  void forgetTexts() {
    try {
      db.sql(
              "UPDATE ai_call SET prompt_text = NULL, output_text = NULL WHERE created_at < :before"
                  + " AND (prompt_text IS NOT NULL OR output_text IS NOT NULL)")
          .param(
              "before",
              Timestamp.from(clock.instant().minus(settings.retentionDays(), ChronoUnit.DAYS)))
          .update();
    } catch (RuntimeException e) {
      LOG.warn("Deleting old AI call texts failed", e);
    }
  }

  private void log(
      Request r,
      String route,
      String outcome,
      int in,
      int out,
      int millis,
      String prompt,
      String output,
      Instant now) {
    db.sql(
            """
            INSERT INTO ai_call (id, feature, route, prompt_version, subject_id, outcome, tokens_in,
                                 tokens_out, millis, prompt_text, output_text, created_at)
            VALUES (:id, :f, :r, :v, :s, :o, :in, :out, :ms, :p, :t, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("f", r.feature().name())
        .param("r", route)
        .param("v", r.promptVersion())
        .param("s", r.subjectId())
        .param("o", outcome)
        .param("in", in)
        .param("out", out)
        .param("ms", millis)
        .param("p", prompt)
        .param("t", output)
        .param("now", Timestamp.from(now))
        .update();
  }
}
