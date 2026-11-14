package care.solace.tracking.episodes;

import care.solace.tracking.episodes.EpisodeModel.Metric;
import care.solace.tracking.episodes.EpisodeModel.MetricKind;
import care.solace.tracking.episodes.EpisodeModel.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The published episode models (Tracking Service Spec, Episode models; D61): the newest published
 * version of each code. Admins publish and retire models in the portal, so this instance reads them
 * again after each change and every {@code solace.tracking.elements.refresh-seconds} so other
 * instances catch up, as the element library does.
 */
@Component
public class EpisodeModels {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient db;
  private volatile Map<String, EpisodeModel> byCode;

  @Autowired
  EpisodeModels(JdbcClient db) {
    this.db = db;
    reload();
  }

  private EpisodeModels(Collection<EpisodeModel> models) {
    this.db = null;
    this.byCode = index(models);
  }

  /** A fixed set of models, for tests and test runs that do not read the database. */
  public static EpisodeModels of(Collection<EpisodeModel> models) {
    return new EpisodeModels(models);
  }

  /** Reads the published models again; readers keep the old set until the new one is complete. */
  @Scheduled(
      fixedDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      initialDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      timeUnit = TimeUnit.SECONDS)
  public void reload() {
    if (db == null) {
      return;
    }
    byCode =
        index(
            db.sql("SELECT * FROM episode_model WHERE status = 'PUBLISHED' ORDER BY code, version")
                .query(EpisodeModels::row)
                .list());
  }

  private static Map<String, EpisodeModel> index(Collection<EpisodeModel> models) {
    Map<String, EpisodeModel> out = new LinkedHashMap<>();
    // Ordered by version, so the newest of each code is put last and wins.
    models.forEach(m -> out.put(m.code(), m));
    return java.util.Collections.unmodifiableMap(out);
  }

  public List<EpisodeModel> all() {
    return List.copyOf(byCode.values());
  }

  public Optional<EpisodeModel> find(String code) {
    return Optional.ofNullable(byCode.get(code));
  }

  /** The models that read any of these elements. */
  public List<EpisodeModel> reading(Collection<String> elementCodes) {
    return byCode.values().stream()
        .filter(m -> m.elements().stream().anyMatch(elementCodes::contains))
        .toList();
  }

  /**
   * A model from its stored JSON parts.
   *
   * @param metrics [{"code", "kind", "value"?, "labels"}]
   */
  public static EpisodeModel parse(
      String code,
      int version,
      String startElement,
      String endElement,
      String activityElement,
      List<String> activeValues,
      int gapDays,
      String span,
      JsonNode metrics,
      JsonNode labels) {
    List<Metric> parsed = new ArrayList<>();
    for (JsonNode m : metrics) {
      parsed.add(
          new Metric(
              m.path("code").asText(),
              MetricKind.valueOf(m.path("kind").asText().toUpperCase(Locale.ROOT)),
              m.hasNonNull("value") ? m.path("value").asText() : null,
              m.path("labels")));
    }
    return new EpisodeModel(
        code,
        version,
        startElement,
        endElement,
        activityElement,
        new LinkedHashSet<>(activeValues),
        gapDays,
        Span.valueOf(span),
        List.copyOf(parsed),
        labels);
  }

  private static EpisodeModel row(ResultSet rs, int n) throws SQLException {
    try {
      return parse(
          rs.getString("code"),
          rs.getInt("version"),
          rs.getString("start_element"),
          rs.getString("end_element"),
          rs.getString("activity_element"),
          Arrays.asList((String[]) rs.getArray("active_values").getArray()),
          rs.getInt("gap_days"),
          rs.getString("span"),
          JSON.readTree(rs.getString("metrics_json")),
          JSON.readTree(rs.getString("labels_json")));
    } catch (JsonProcessingException e) {
      throw new SQLException("Bad episode model JSON", e);
    }
  }
}
