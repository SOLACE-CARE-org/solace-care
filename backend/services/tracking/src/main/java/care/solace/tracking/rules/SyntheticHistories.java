package care.solace.tracking.rules;

import care.solace.tracking.context.ContextQuestion;
import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.Episode;
import care.solace.tracking.episodes.EpisodeModel;
import care.solace.tracking.episodes.Episodes;
import care.solace.tracking.sync.Sync;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Made-up patient histories the builder tests drafts against (Tracking Service Spec, Testing before
 * publishing; D61). Real patient data is never used. A history is data: a recipe of parts, each a
 * pattern for one element, built back from today so window rules see it as recent.
 *
 * <ul>
 *   <li>{@code constant}: {@code value} on each of the last {@code days} days;
 *   <li>{@code alternating}: {@code values} in turn, today first, over {@code days} days;
 *   <li>{@code sparse}: {@code value} on one day in {@code every}, today first, over {@code days};
 *   <li>{@code episodes}: complete episodes of {@code lengths}, oldest first, then an open one
 *       whose day today is {@code currentDay}. Each starts with the {@code start} event; {@code
 *       activity} is logged as the {@code active} runs from its first day, then the {@code after}
 *       run, cut at the next start.
 * </ul>
 */
public final class SyntheticHistories {

  static final int MAX_PARTS = 8;
  static final int MAX_DAYS = 366;

  /**
   * One made-up person: what they logged, and their context answers for "unless".
   *
   * @param daily day-state values per element
   * @param events the days of each event element, such as period_start
   */
  public record History(
      String code,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      Map<String, SortedSet<LocalDate>> events,
      Map<String, JsonNode> answers) {

    /** The signals the rules read, with episodes found by each model as the reconciler would. */
    public RuleEngine.Signals signals(LocalDate today, Collection<EpisodeModel> models) {
      Map<String, List<Episode>> episodes = new LinkedHashMap<>();
      for (EpisodeModel m : models) {
        Map<LocalDate, String> activity = new TreeMap<>();
        if (m.activityElement() != null) {
          daily
              .getOrDefault(m.activityElement(), new TreeMap<>())
              .forEach((d, v) -> activity.put(d, String.valueOf(v)));
        }
        episodes.put(
            m.code(),
            Episodes.find(m, activity, eventDays(m.startElement()), eventDays(m.endElement())));
      }
      return new RuleEngine.Signals(today, daily, episodes);
    }

    private SortedSet<LocalDate> eventDays(String element) {
      return element == null ? new TreeSet<>() : events.getOrDefault(element, new TreeSet<>());
    }
  }

  private SyntheticHistories() {}

  /** Builds a checked recipe into a history ending today. */
  public static History build(String code, JsonNode recipe, JsonNode context, LocalDate today) {
    Map<String, NavigableMap<LocalDate, Object>> daily = new LinkedHashMap<>();
    Map<String, SortedSet<LocalDate>> events = new LinkedHashMap<>();
    for (JsonNode part : recipe.path("parts")) {
      switch (part.path("pattern").asText()) {
        case "constant" -> {
          var values = daily.computeIfAbsent(part.path("element").asText(), k -> new TreeMap<>());
          for (int i = 0; i < part.path("days").asInt(); i++) {
            values.put(today.minusDays(i), value(part.path("value")));
          }
        }
        case "alternating" -> {
          var values = daily.computeIfAbsent(part.path("element").asText(), k -> new TreeMap<>());
          JsonNode turn = part.path("values");
          for (int i = 0; i < part.path("days").asInt(); i++) {
            values.put(today.minusDays(i), value(turn.get(i % turn.size())));
          }
        }
        case "sparse" -> {
          var values = daily.computeIfAbsent(part.path("element").asText(), k -> new TreeMap<>());
          for (int i = 0; i < part.path("days").asInt(); i += part.path("every").asInt()) {
            values.put(today.minusDays(i), value(part.path("value")));
          }
        }
        case "episodes" -> episodes(part, today, daily, events);
        default -> throw new IllegalArgumentException("Unchecked recipe");
      }
    }
    Map<String, JsonNode> answers = new LinkedHashMap<>();
    if (context != null) {
      context.properties().forEach(e -> answers.put(e.getKey(), e.getValue()));
    }
    return new History(code, daily, events, answers);
  }

  private static void episodes(
      JsonNode part,
      LocalDate today,
      Map<String, NavigableMap<LocalDate, Object>> daily,
      Map<String, SortedSet<LocalDate>> events) {
    List<Integer> lengths = new ArrayList<>();
    part.path("lengths").forEach(l -> lengths.add(l.asInt()));
    int total = lengths.stream().mapToInt(Integer::intValue).sum();
    // The newest episode is open: it started currentDay - 1 days ago.
    LocalDate start = today.minusDays(total + part.path("currentDay").asInt() - 1L);
    var starts = events.computeIfAbsent(part.path("start").asText(), k -> new TreeSet<>());
    NavigableMap<LocalDate, Object> activity =
        part.hasNonNull("activity")
            ? daily.computeIfAbsent(part.path("activity").asText(), k -> new TreeMap<>())
            : null;
    for (int i = 0; i <= lengths.size(); i++) {
      starts.add(start);
      // The open episode has no next start to cut it, so its "after" run is not logged.
      int length = i < lengths.size() ? lengths.get(i) : 0;
      if (activity != null) {
        int d = 0;
        for (JsonNode run : part.path("active")) {
          for (int n = 0; n < run.path("days").asInt(); n++, d++) {
            activity.put(start.plusDays(d), value(run.path("value")));
          }
        }
        JsonNode after = part.path("after");
        for (int n = 0; n < after.path("days").asInt() && d < length; n++, d++) {
          activity.put(start.plusDays(d), value(after.path("value")));
        }
      }
      start = start.plusDays(length);
    }
  }

  /** The value as the rules read it: a number, a code or a yes or no. */
  private static Object value(JsonNode v) {
    if (v.isIntegralNumber()) {
      return v.asInt();
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    return v.asText();
  }

  /**
   * What is wrong with a recipe and its context answers, given the published elements and context
   * questions; empty when it can be built. Notes and dates are never logged by a recipe, since no
   * rule reads notes and events come only from episodes.
   */
  public static List<String> problems(
      JsonNode recipe,
      JsonNode context,
      Function<String, Element> elements,
      Function<String, ContextQuestion> questions) {
    List<String> problems = new ArrayList<>();
    JsonNode parts = recipe == null ? null : recipe.path("parts");
    if (parts == null || !parts.isArray() || parts.isEmpty() || parts.size() > MAX_PARTS) {
      problems.add("recipe");
      return problems;
    }
    Set<String> logged = new TreeSet<>();
    for (int i = 0; i < parts.size(); i++) {
      JsonNode part = parts.get(i);
      if (!partFine(part, elements, logged)) {
        problems.add("recipe.parts[" + i + "]");
      }
    }
    if (context != null && !context.isNull()) {
      if (!context.isObject()) {
        problems.add("context");
      } else {
        for (Iterator<String> it = context.fieldNames(); it.hasNext(); ) {
          String code = it.next();
          ContextQuestion q = questions.apply(code);
          if (q == null || !q.accepts(context.get(code))) {
            problems.add("context." + code);
          }
        }
      }
    }
    return problems;
  }

  private static boolean partFine(
      JsonNode part, Function<String, Element> elements, Set<String> logged) {
    String pattern = part.path("pattern").asText();
    if (pattern.equals("episodes")) {
      Element start = elements.apply(part.path("start").asText());
      if (start == null || start.kind() != Kind.EVENT || !logged.add(start.code())) {
        return false;
      }
      JsonNode lengths = part.path("lengths");
      int currentDay = part.path("currentDay").asInt(0);
      if (!lengths.isArray()
          || lengths.isEmpty()
          || lengths.size() > 24
          || currentDay < 1
          || currentDay > 180) {
        return false;
      }
      int total = currentDay;
      for (JsonNode l : lengths) {
        if (!l.isIntegralNumber() || l.asInt() < 1 || l.asInt() > 180) {
          return false;
        }
        total += l.asInt();
      }
      if (total > MAX_DAYS * 2) {
        return false;
      }
      if (!part.hasNonNull("activity")) {
        return !part.has("active") && !part.has("after");
      }
      Element activity = dayState(elements.apply(part.path("activity").asText()));
      if (activity == null || !logged.add(activity.code())) {
        return false;
      }
      JsonNode active = part.path("active");
      if (!active.isArray() || active.isEmpty() || active.size() > 4) {
        return false;
      }
      for (JsonNode run : active) {
        if (!run(run, activity)) {
          return false;
        }
      }
      return !part.has("after") || run(part.path("after"), activity);
    }
    Element e = dayState(elements.apply(part.path("element").asText()));
    int days = part.path("days").asInt(0);
    if (e == null || days < 1 || days > MAX_DAYS || !logged.add(e.code())) {
      return false;
    }
    return switch (pattern) {
      case "constant" -> fits(e, part.get("value"));
      case "sparse" -> {
        int every = part.path("every").asInt(0);
        yield every >= 2 && every <= 30 && fits(e, part.get("value"));
      }
      case "alternating" -> {
        JsonNode values = part.path("values");
        boolean fine = values.isArray() && values.size() >= 2 && values.size() <= 14;
        for (JsonNode v : values) {
          fine &= fits(e, v);
        }
        yield fine;
      }
      default -> false;
    };
  }

  /** A day-state element a rule can read: not a note, not a date. */
  private static Element dayState(Element e) {
    return e == null
            || e.kind() != Kind.DAY_STATE
            || e.isNote()
            || e.valueType() == ValueType.DATE
            || e.valueType() == ValueType.TEXT
        ? null
        : e;
  }

  private static boolean run(JsonNode run, Element e) {
    int days = run.path("days").asInt(0);
    return run.isObject() && days >= 1 && days <= 60 && fits(e, run.get("value"));
  }

  private static boolean fits(Element e, JsonNode value) {
    return value != null && Sync.parse(e, value).isPresent();
  }
}
