package care.solace.tracking.episodes;

import care.solace.tracking.PersonalData;
import care.solace.tracking.sync.Sync;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rebuilds a person's episodes for every model that reads an element they changed, in the same
 * transaction and before the pattern check (Tracking Service Spec, Episode models). Episodes are
 * derived data, so erasing or editing observations rebuilds them.
 */
@Component
public class EpisodeReconciler implements PersonalData {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, Integer>> METRICS =
      new TypeReference<>() {};

  private final JdbcClient db;
  private final Sync sync;
  private final EpisodeModels models;
  private final Clock clock;

  EpisodeReconciler(JdbcClient db, Sync sync, EpisodeModels models, Clock clock) {
    this.db = db;
    this.sync = sync;
    this.models = models;
    this.clock = clock;
  }

  @EventListener
  @Order(1)
  void on(Sync.EntriesChanged changed) {
    for (var model : models.reading(changed.elementCodes())) {
      rebuild(changed.userId(), model);
    }
  }

  public List<Episode> rebuild(UUID user, EpisodeModel model) {
    Map<LocalDate, String> activity = new HashMap<>();
    SortedSet<LocalDate> starts = new TreeSet<>();
    SortedSet<LocalDate> ends = new TreeSet<>();
    for (var o : sync.live(user, model.elements(), LocalDate.of(2000, 1, 1))) {
      LocalDate day = o.value().date() != null ? o.value().date() : o.observedOn();
      if (o.elementCode().equals(model.startElement())) {
        starts.add(day);
      } else if (o.elementCode().equals(model.endElement())) {
        ends.add(day);
      } else if (o.elementCode().equals(model.activityElement())) {
        String value = text(o.value());
        if (value != null) {
          activity.put(o.observedOn(), value);
        }
      }
    }
    var episodes = Episodes.find(model, activity, starts, ends);
    db.sql("DELETE FROM episode WHERE user_id = :u AND model_code = :m")
        .param("u", user)
        .param("m", model.code())
        .update();
    for (var e : episodes) {
      db.sql(
              """
              INSERT INTO episode (user_id, model_code, start_date, end_date, active_end_date,
                                   complete, metrics_json)
              VALUES (:u, :m, :s, :e, :ae, :c, CAST(:metrics AS jsonb))
              """)
          .param("u", user)
          .param("m", model.code())
          .param("s", Date.valueOf(e.start()))
          .param("e", e.end() == null ? null : Date.valueOf(e.end()))
          .param("ae", e.activeEnd() == null ? null : Date.valueOf(e.activeEnd()))
          .param("c", e.complete())
          .param("metrics", json(e.metrics()))
          .update();
    }
    return episodes;
  }

  /** Stored episodes of every model, oldest first within each. */
  public Map<String, List<Episode>> episodes(UUID user) {
    Map<String, List<Episode>> out = new LinkedHashMap<>();
    for (var model : models.all()) {
      out.put(model.code(), episodes(user, model.code()));
    }
    return out;
  }

  /** Stored episodes of one model, oldest first. */
  public List<Episode> episodes(UUID user, String model) {
    return db.sql(
            "SELECT * FROM episode WHERE user_id = :u AND model_code = :m ORDER BY start_date")
        .param("u", user)
        .param("m", model)
        .query(
            (rs, n) -> {
              Date end = rs.getDate("end_date");
              Date activeEnd = rs.getDate("active_end_date");
              return new Episode(
                  rs.getString("model_code"),
                  rs.getDate("start_date").toLocalDate(),
                  end == null ? null : end.toLocalDate(),
                  activeEnd == null ? null : activeEnd.toLocalDate(),
                  rs.getBoolean("complete"),
                  metrics(rs.getString("metrics_json")));
            })
        .list();
  }

  /** Day of the newest episode, counting its first day as 1, on the person's local day. */
  public Optional<Integer> currentDay(UUID user, String model, ZoneId zone) {
    var all = episodes(user, model);
    if (all.isEmpty()) {
      return Optional.empty();
    }
    LocalDate today = LocalDate.now(clock.withZone(zone));
    long day = ChronoUnit.DAYS.between(all.get(all.size() - 1).start(), today) + 1;
    return day < 1 ? Optional.empty() : Optional.of((int) day);
  }

  @Override
  public void erase(UUID userId) {
    db.sql("DELETE FROM episode WHERE user_id = :u").param("u", userId).update();
  }

  /** An activity value as the model's active values name it. */
  static String text(Sync.Value value) {
    if (value.text() != null) {
      return value.text();
    }
    if (value.integer() != null) {
      return String.valueOf(value.integer());
    }
    return value.bool() == null ? null : String.valueOf(value.bool());
  }

  private static String json(Map<String, Integer> metrics) {
    try {
      return JSON.writeValueAsString(metrics);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Integer> metrics(String json) {
    try {
      return JSON.readValue(json, METRICS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Bad episode metrics", e);
    }
  }
}
