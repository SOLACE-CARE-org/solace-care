package care.solace.tracking.elements;

import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.elements.InputViews.InputView;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What an element draft must be before review (Tracking Service Spec, Elements; D61), and how a new
 * version is numbered. Pure, so the builder and the tests share it.
 */
public final class ElementRules {

  /**
   * The icon keys the app ships for elements, drawn in the module's colour and offline. Like an
   * input view, a new icon is app code.
   */
  public static final Set<String> ICONS =
      new TreeSet<>(
          Set.of(
              "mood",
              "moon",
              "pen",
              "droplet",
              "calendar-start",
              "calendar-end",
              "skin",
              "hair",
              "bolt",
              "heart",
              "sun",
              "activity",
              "pill",
              "thermometer",
              "utensils",
              "brain"));

  public static final int MAX_VALUES = 12;
  public static final int MAX_NAME = 60;
  public static final int MAX_HELP = 200;
  public static final int MAX_POINTS = 11;

  private static final String CODE = "^[a-z][a-z0-9_]*$";

  /** What an admin sets on an element; signals and the app version are derived. */
  public record Draft(
      String code,
      Kind kind,
      ValueType valueType,
      JsonNode constraints,
      String inputView,
      JsonNode viewProps,
      String icon,
      JsonNode labels) {}

  private ElementRules() {}

  /** Every non-note element gives a daily signal; episode models that read it add theirs. */
  public static List<String> signals(ValueType valueType) {
    return valueType == ValueType.TEXT ? List.of() : List.of("daily_value");
  }

  /**
   * What stops a draft being submitted; empty when it is ready: constraints, inputView, viewProps,
   * icon, labels (EN and FR names, help in both or neither) and values (a label per value or point
   * in both languages).
   */
  public static List<String> problems(Draft d, InputView view) {
    List<String> problems = new ArrayList<>();
    boolean constraintsFine = constraintsFine(d.valueType(), d.constraints());
    if (!constraintsFine) {
      problems.add("constraints");
    }
    if (view == null || !view.accepts(d.kind(), d.valueType())) {
      problems.add("inputView");
    } else {
      if (!propsFine(view, d.viewProps(), constraintsFine ? d.constraints() : null)) {
        problems.add("viewProps");
      }
      if (view.key().equals("scale_5") && constraintsFine && points(d.constraints()) != 5) {
        problems.add("constraints");
      }
    }
    if (d.icon() == null || !ICONS.contains(d.icon())) {
      problems.add("icon");
    }
    JsonNode en = d.labels() == null ? null : d.labels().get("en");
    JsonNode fr = d.labels() == null ? null : d.labels().get("fr");
    if (!text(en, "name", MAX_NAME) || !text(fr, "name", MAX_NAME) || !helpFine(en, fr)) {
      problems.add("labels");
    }
    if (constraintsFine && (!valuesFine(d, en) || !valuesFine(d, fr))) {
      problems.add("values");
    }
    return List.copyOf(new LinkedHashSet<>(problems));
  }

  /**
   * The number of a new version after {@code newest}, the newest version of the code: adding enum
   * values is a minor version, any other change to the kind, value type or constraints a major one,
   * and a change only to labels, view props or the icon a minor one.
   *
   * @return {major, minor}
   */
  public static int[] number(Draft d, ElementLibrary.Element newest) {
    boolean sameShape = d.kind() == newest.kind() && d.valueType() == newest.valueType();
    boolean major;
    if (!sameShape) {
      major = true;
    } else if (d.valueType() == ValueType.ENUM) {
      List<String> before = strings(newest.constraints().path("values"));
      List<String> now = strings(d.constraints() == null ? null : d.constraints().path("values"));
      major = !now.containsAll(before);
    } else {
      major = d.constraints() == null || !d.constraints().equals(newest.constraints());
    }
    return major
        ? new int[] {newest.major() + 1, 0}
        : new int[] {newest.major(), newest.minor() + 1};
  }

  static boolean constraintsFine(ValueType type, JsonNode c) {
    if (c == null || !c.isObject()) {
      return false;
    }
    Set<String> keys = new HashSet<>();
    c.fieldNames().forEachRemaining(keys::add);
    return switch (type) {
      case INT ->
          keys.equals(Set.of("min", "max"))
              && ints(c, "min", "max")
              && c.get("min").asInt() < c.get("max").asInt()
              && points(c) <= MAX_POINTS;
      case MINUTES -> {
        if (!keys.equals(Set.of("min", "max", "step")) || !ints(c, "min", "max", "step")) {
          yield false;
        }
        int min = c.get("min").asInt();
        int max = c.get("max").asInt();
        int step = c.get("step").asInt();
        yield min >= 0
            && max <= 1440
            && min < max
            && step >= 1
            && step <= 60
            && min % step == 0
            && max % step == 0;
      }
      case ENUM -> {
        if (!keys.equals(Set.of("values")) || !c.get("values").isArray()) {
          yield false;
        }
        List<String> values = strings(c.get("values"));
        yield values.size() >= 2
            && values.size() <= MAX_VALUES
            && values.size() == c.get("values").size()
            && new HashSet<>(values).size() == values.size()
            && values.stream().allMatch(v -> v.matches(CODE) && v.length() <= 40);
      }
      case BOOL, DATE -> keys.isEmpty();
      case TEXT ->
          keys.isEmpty()
              || (keys.equals(Set.of("max"))
                  && ints(c, "max")
                  && c.get("max").asInt() >= 1
                  && c.get("max").asInt() <= 4000);
    };
  }

  /** Props the view's schema allows, of the right type and range; a start fits the constraints. */
  static boolean propsFine(InputView view, JsonNode props, JsonNode constraints) {
    if (props == null || props.isNull()) {
      return true;
    }
    if (!props.isObject()) {
      return false;
    }
    for (var it = props.properties().iterator(); it.hasNext(); ) {
      var prop = it.next();
      JsonNode schema = view.props().get(prop.getKey());
      JsonNode v = prop.getValue();
      if (schema == null) {
        return false;
      }
      boolean fine =
          switch (schema.path("type").asText()) {
            case "integer" ->
                v.isIntegralNumber()
                    && (!schema.has("minimum") || v.asInt() >= schema.get("minimum").asInt())
                    && (!schema.has("maximum") || v.asInt() <= schema.get("maximum").asInt());
            case "string" -> v.isTextual() && !v.asText().isBlank() && v.asText().length() <= 60;
            case "boolean" -> v.isBoolean();
            default -> false;
          };
      if (!fine) {
        return false;
      }
    }
    // Where the stepper opens must be a value the element accepts.
    if (props.has("start") && constraints != null && constraints.has("min")) {
      int start = props.get("start").asInt();
      int step = constraints.path("step").asInt(1);
      return start >= constraints.get("min").asInt()
          && start <= constraints.get("max").asInt()
          && start % step == 0;
    }
    return true;
  }

  private static boolean valuesFine(Draft d, JsonNode lang) {
    JsonNode values = lang == null ? null : lang.get("values");
    List<String> wanted =
        switch (d.valueType()) {
          case ENUM -> strings(d.constraints().get("values"));
          case INT -> {
            List<String> points = new ArrayList<>();
            for (int i = d.constraints().get("min").asInt();
                i <= d.constraints().get("max").asInt();
                i++) {
              points.add(String.valueOf(i));
            }
            yield points;
          }
          default -> List.of();
        };
    if (wanted.isEmpty()) {
      return values == null || values.isEmpty();
    }
    if (values == null || !values.isObject() || values.size() != wanted.size()) {
      return false;
    }
    return wanted.stream().allMatch(v -> text(values, v, MAX_NAME));
  }

  private static boolean helpFine(JsonNode en, JsonNode fr) {
    boolean enHas = en != null && en.hasNonNull("help") && !en.get("help").asText().isBlank();
    boolean frHas = fr != null && fr.hasNonNull("help") && !fr.get("help").asText().isBlank();
    return enHas == frHas && (!enHas || (text(en, "help", MAX_HELP) && text(fr, "help", MAX_HELP)));
  }

  private static boolean text(JsonNode node, String field, int max) {
    if (node == null || !node.path(field).isTextual()) {
      return false;
    }
    String s = node.get(field).asText().strip();
    return !s.isEmpty() && s.length() <= max;
  }

  private static int points(JsonNode c) {
    return c.get("max").asInt() - c.get("min").asInt() + 1;
  }

  private static boolean ints(JsonNode c, String... fields) {
    for (String f : fields) {
      if (!c.get(f).isIntegralNumber()) {
        return false;
      }
    }
    return true;
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(v -> out.add(v.isTextual() ? v.asText() : ""));
    }
    return out;
  }
}
