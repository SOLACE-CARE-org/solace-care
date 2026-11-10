package care.solace.tracking.rules;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The signals a module's rules may read, derived from its bound elements and the episode models
 * that read them (Tracking Service Spec, Signals; D61). The rule validator and the builder's
 * vocabulary both come from here, so neither keeps its own list.
 */
public final class SignalCatalog {

  public enum Kind {
    /** An element's value per local day, read over a window. */
    DAILY,
    /** A number per complete episode of an episode model, read over consecutive episodes. */
    EPISODE
  }

  /**
   * One signal a rule may read.
   *
   * @param key {@code daily_value:<element>}, or the episode signal's code
   * @param signal the condition's {@code signal} field: daily_value, or {@code <model>.<metric>}
   * @param element the element, for daily signals; null for episode signals
   * @param valueType the element's value type for daily signals; INT for episode signals
   * @param values the allowed values of an ENUM element; empty otherwise
   * @param labels the metric's EN/FR labels, for episode signals; null for daily signals
   */
  public record Signal(
      String key,
      String signal,
      String element,
      Kind kind,
      ValueType valueType,
      List<String> values,
      JsonNode labels) {

    public boolean numeric() {
      return valueType == ValueType.INT || valueType == ValueType.MINUTES;
    }
  }

  private SignalCatalog() {}

  /** An element read day by day: any element that declares a daily value. */
  public static boolean offersDailyValue(Element element) {
    return element.signals().contains("daily_value");
  }

  /**
   * The module's signals: one daily signal per readable element, then the signals of every episode
   * model whose start or activity element the module binds.
   */
  public static List<Signal> forModule(
      Map<String, Element> bound, Collection<EpisodeModel> models) {
    List<Signal> out = new ArrayList<>();
    for (Element e : bound.values()) {
      if (offersDailyValue(e)) {
        out.add(
            new Signal(
                "daily_value:" + e.code(),
                "daily_value",
                e.code(),
                Kind.DAILY,
                e.valueType(),
                enumValues(e),
                null));
      }
    }
    for (EpisodeModel m : models) {
      if (bound.containsKey(m.startElement())
          || (m.activityElement() != null && bound.containsKey(m.activityElement()))) {
        for (var metric : m.metrics()) {
          String code = m.signal(metric);
          out.add(
              new Signal(
                  code, code, null, Kind.EPISODE, ValueType.INT, List.of(), metric.labels()));
        }
      }
    }
    return out;
  }

  /** The signal a condition names, if this module offers it. */
  public static Optional<Signal> find(
      JsonNode condition, Map<String, Element> bound, Collection<EpisodeModel> models) {
    String signal = condition.path("signal").asText();
    String key =
        signal.equals("daily_value") ? "daily_value:" + condition.path("element").asText() : signal;
    return forModule(bound, models).stream().filter(s -> s.key().equals(key)).findFirst();
  }

  private static List<String> enumValues(Element e) {
    if (e.valueType() != ValueType.ENUM) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    e.constraints().path("values").forEach(v -> values.add(v.asText()));
    return values;
  }
}
