package care.solace.tracking.rules;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The signals a module's rules may read, derived from its bound elements (Tracking Service Spec,
 * Signals; D61). The rule validator and the builder's vocabulary both come from here, so neither
 * keeps its own list.
 */
public final class SignalCatalog {

  public enum Kind {
    /** An element's value per local day, read over a window. */
    DAILY,
    /** A number per complete cycle, read over consecutive cycles. */
    CYCLE
  }

  /**
   * One signal a rule may read.
   *
   * @param key {@code daily_value:<element>} or the cycle signal's code
   * @param signal the condition's {@code signal} field
   * @param element the element, for daily signals; null for cycle signals
   * @param valueType the element's value type for daily signals; INT for cycle signals
   * @param values the allowed values of an ENUM element; empty otherwise
   */
  public record Signal(
      String key,
      String signal,
      String element,
      Kind kind,
      ValueType valueType,
      List<String> values) {

    public boolean numeric() {
      return valueType == ValueType.INT || valueType == ValueType.MINUTES;
    }
  }

  private SignalCatalog() {}

  /**
   * An element read day by day: any element except a note or a bare event. Cycle elements count
   * too, since pcos.no_period counts flow day by day (D54).
   */
  public static boolean offersDailyValue(Element element) {
    return element.signals().contains("daily_value")
        || element.signals().stream().anyMatch(CycleMetrics::isCycleSignal);
  }

  /** The module's signals: one daily signal per readable element, then its cycle signals. */
  public static List<Signal> forModule(Map<String, Element> bound) {
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
                enumValues(e)));
      }
    }
    for (String code : CycleMetrics.codes()) {
      if (bound.values().stream().anyMatch(e -> e.signals().contains(code))) {
        out.add(new Signal(code, code, null, Kind.CYCLE, ValueType.INT, List.of()));
      }
    }
    return out;
  }

  /** The signal a condition names, if this module offers it. */
  public static Optional<Signal> find(JsonNode condition, Map<String, Element> bound) {
    String signal = condition.path("signal").asText();
    String key =
        signal.equals("daily_value") ? "daily_value:" + condition.path("element").asText() : signal;
    return forModule(bound).stream().filter(s -> s.key().equals(key)).findFirst();
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
