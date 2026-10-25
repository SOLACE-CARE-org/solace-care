package care.solace.ai.features;

import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import care.solace.coresettings.CoreSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The bilingual evaluation harness (System Design, AI service, Evaluation; D56): every feature has
 * made-up test cases in English and French under {@code /eval/<feature>.json}; a prompt version
 * passes when at least the gate share of cases pass in each language. Failures name the case and
 * the guardrail.
 */
@Service
public class Evaluations {

  /** One feature's way of running a test case with a given prompt version. */
  public interface Runner {

    Feature feature();

    /** Why the case failed, or empty when it passed. */
    Optional<String> run(
        Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale);
  }

  public record Failure(String caseId, String locale, String reason) {}

  public record Run(
      UUID id,
      Feature feature,
      UUID promptVersionId,
      int passedEn,
      int totalEn,
      int passedFr,
      int totalFr,
      BigDecimal gate,
      boolean passed,
      List<Failure> failures,
      String ranBy,
      Instant ranAt) {}

  private final CoreSettings core;
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Map<Feature, Runner> runners;
  private final List<Runner> lazyRunners;
  private final BigDecimal gate;
  private final Clock clock;
  private final org.springframework.beans.factory.ObjectProvider<care.solace.lists.ReviewedLists>
      lists;

  Evaluations(
      CoreSettings core,
      JdbcClient db,
      ObjectMapper json,
      @Lazy List<Runner> runners,
      @Value("${solace.ai.eval-gate:95}") BigDecimal gate,
      Clock clock,
      org.springframework.beans.factory.ObjectProvider<care.solace.lists.ReviewedLists> lists) {
    this.lists = lists;
    this.core = core;
    this.db = db;
    this.json = json;
    this.runners = new java.util.HashMap<>();
    this.lazyRunners = runners;
    this.gate = gate;
    this.clock = clock;
  }

  private Runner runner(Feature feature) {
    if (runners.isEmpty()) {
      runners.putAll(
          lazyRunners.stream().collect(Collectors.toMap(Runner::feature, Function.identity())));
    }
    return runners.get(feature);
  }

  /** Runs every test case of the version's feature with that version. */
  public Run evaluate(Features.PromptVersion version, String by) {
    return evaluate(version, by, null);
  }

  /**
   * Runs the feature's test cases with its live version on a pending route (D61). The run is kept
   * apart from the version's own evaluations: it decides only whether the route may go live.
   */
  public Run evaluateOnRoute(
      Features.PromptVersion live, List<String> route, String by, ModelGateway gateway) {
    return gateway.trying(live.feature(), route, () -> evaluate(live, by, String.join(",", route)));
  }

  /** The latest run of the feature's live version on exactly this route, if any. */
  public Optional<Run> latestOnRoute(UUID promptVersionId, List<String> route) {
    return runs(
        "SELECT * FROM eval_run WHERE prompt_version_id = :v AND routes = :r"
            + " ORDER BY ran_at DESC LIMIT 1",
        promptVersionId,
        String.join(",", route));
  }

  private Run evaluate(Features.PromptVersion version, String by, String routes) {
    Runner runner = runner(version.feature());
    JsonNode cases = cases(version.feature());
    int passedEn = 0;
    int totalEn = 0;
    int passedFr = 0;
    int totalFr = 0;
    List<Failure> failures = new ArrayList<>();
    for (JsonNode c : cases) {
      String locale = c.path("locale").asText("en");
      Optional<String> failure =
          runner == null
              ? Optional.of("no-runner")
              : runner.run(
                  version, c.path("input"), c.path("expect"), Locale.forLanguageTag(locale));
      boolean fr = locale.equals("fr");
      if (fr) {
        totalFr++;
      } else {
        totalEn++;
      }
      if (failure.isEmpty()) {
        if (fr) {
          passedFr++;
        } else {
          passedEn++;
        }
      } else {
        failures.add(new Failure(c.path("id").asText(), locale, failure.get()));
      }
    }
    boolean passed =
        totalEn > 0 && totalFr > 0 && share(passedEn, totalEn) && share(passedFr, totalFr);
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    db.sql(
            """
            INSERT INTO eval_run (id, feature, prompt_version_id, passed_en, total_en, passed_fr, total_fr,
                                  gate, passed, failures_json, ran_by, ran_at, routes)
            VALUES (:id, :f, :v, :pe, :te, :pf, :tf, :g, :p, CAST(:fail AS jsonb), :by, :now, :r)
            """)
        .param("r", routes)
        .param("id", id)
        .param("f", version.feature().name())
        .param("v", version.id())
        .param("pe", passedEn)
        .param("te", totalEn)
        .param("pf", passedFr)
        .param("tf", totalFr)
        .param("g", gate())
        .param("p", passed)
        .param("fail", write(failures))
        .param("by", by)
        .param("now", Timestamp.from(now))
        .update();
    return runs("SELECT * FROM eval_run WHERE id = :v", id, null).orElseThrow();
  }

  public boolean passed(UUID promptVersionId) {
    return latest(promptVersionId).map(Run::passed).orElse(false);
  }

  /** The version's latest own evaluation; runs on a pending route are not counted (D61). */
  public Optional<Run> latest(UUID promptVersionId) {
    return runs(
        "SELECT * FROM eval_run WHERE prompt_version_id = :v AND routes IS NULL"
            + " ORDER BY ran_at DESC LIMIT 1",
        promptVersionId,
        null);
  }

  private Optional<Run> runs(String sql, UUID id, String routes) {
    var spec = db.sql(sql).param("v", id);
    if (routes != null) {
      spec = spec.param("r", routes);
    }
    return spec.query(
            (rs, n) -> {
              try {
                return new Run(
                    rs.getObject("id", UUID.class),
                    Feature.valueOf(rs.getString("feature")),
                    rs.getObject("prompt_version_id", UUID.class),
                    rs.getInt("passed_en"),
                    rs.getInt("total_en"),
                    rs.getInt("passed_fr"),
                    rs.getInt("total_fr"),
                    rs.getBigDecimal("gate"),
                    rs.getBoolean("passed"),
                    List.of(json.readValue(rs.getString("failures_json"), Failure[].class)),
                    rs.getString("ran_by"),
                    rs.getTimestamp("ran_at").toInstant());
              } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .optional();
  }

  private boolean share(int passed, int total) {
    return BigDecimal.valueOf(passed * 100L).compareTo(gate().multiply(BigDecimal.valueOf(total)))
        >= 0;
  }

  /** The cases in force: the clinicians' reviewed list, or the file it started from (D61). */
  JsonNode cases(Feature feature) {
    var reviewed = lists.getIfAvailable();
    if (reviewed != null) {
      return reviewed.inForce(EvalSets.code(feature)).path("cases");
    }
    String path = "/eval/" + feature.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".json";
    try (InputStream in = Evaluations.class.getResourceAsStream(path)) {
      if (in == null) {
        return json.createArrayNode();
      }
      return json.readTree(in).path("cases");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The share of cases that must pass, in percent ([95]; D61). */
  private BigDecimal gate() {
    return BigDecimal.valueOf(core.number("solace.ai.eval-gate", gate.longValue()));
  }
}
