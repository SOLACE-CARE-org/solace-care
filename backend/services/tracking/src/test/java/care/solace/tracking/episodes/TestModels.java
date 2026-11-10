package care.solace.tracking.episodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Episode models for tests, defined as the migrations define them. */
public final class TestModels {

  private static final ObjectMapper JSON = new ObjectMapper();

  private TestModels() {}

  /** menstrual_cycle v1, as V1_8 seeds it. */
  public static EpisodeModel menstrualCycle() {
    return model(
        "menstrual_cycle",
        "period_start",
        "period_end",
        "flow_intensity",
        List.of("light", "medium", "heavy", "very_heavy"),
        10,
        "UNTIL_NEXT_START",
        """
        [{"code": "length_days", "kind": "length_days", "labels": {"en": "Cycle length", "fr": "Durée du cycle"}},
         {"code": "period_days", "kind": "active_days", "labels": {"en": "Period length", "fr": "Durée des règles"}},
         {"code": "very_heavy_days", "kind": "days_with_value", "value": "very_heavy",
          "labels": {"en": "Very heavy days", "fr": "Jours très abondants"}}]
        """);
  }

  /** A made-up attack model: headache days, until each run ends. */
  public static EpisodeModel migraine() {
    return model(
        "migraine",
        "migraine_start",
        null,
        "headache",
        List.of("true"),
        2,
        "UNTIL_END",
        """
        [{"code": "length_days", "kind": "length_days", "labels": {"en": "Attack length", "fr": "Durée de la crise"}},
         {"code": "headache_days", "kind": "days_with_value", "value": "true",
          "labels": {"en": "Headache days", "fr": "Jours de mal de tête"}}]
        """);
  }

  public static EpisodeModels launch() {
    return EpisodeModels.of(List.of(menstrualCycle()));
  }

  private static EpisodeModel model(
      String code,
      String start,
      String end,
      String activity,
      List<String> active,
      int gap,
      String span,
      String metrics) {
    try {
      return EpisodeModels.parse(
          code,
          1,
          start,
          end,
          activity,
          active,
          gap,
          span,
          JSON.readTree(metrics),
          JSON.readTree("{\"en\": \"" + code + "\", \"fr\": \"" + code + "\"}"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
