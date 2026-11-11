package care.solace.tracking.modules;

import care.solace.tracking.elements.ElementLibrary.Element;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import care.solace.tracking.episodes.EpisodeModel;
import care.solace.tracking.rules.SignalCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the app shows a module (Tracking Service Spec, Modules; D61), checked against the module's
 * bindings so the app can draw it without knowing any module or element code:
 *
 * <ul>
 *   <li>{@code title}, {@code saveLabel}: the logging screen's title and save button, EN and FR;
 *   <li>{@code episode}: {@code {model, dayLabel, historyTitle}}, a model of a bound element whose
 *       current day shows in the header ({@code dayLabel} holds {@code {{n}}});
 *   <li>{@code trends}: the module's signal keys the Trends screen charts, in order;
 *   <li>{@code quickLog}: a bound element offered as one tap on Home.
 * </ul>
 *
 * Binding groups show elements together under one EN/FR label; a group's elements sit next to each
 * other and share the label.
 */
public final class Presentation {

  static final int MAX_TEXT = 60;
  static final int MAX_TRENDS = 12;

  private Presentation() {}

  /** Problems with a version's presentation, given its bound elements; empty when fine. */
  public static List<String> problems(
      JsonNode presentation, Map<String, Element> bound, Collection<EpisodeModel> models) {
    List<String> problems = new ArrayList<>();
    if (presentation == null || presentation.isNull() || presentation.isMissingNode()) {
      return problems;
    }
    if (!presentation.isObject()) {
      problems.add("presentation");
      return problems;
    }
    for (String text : List.of("title", "saveLabel")) {
      if (presentation.has(text) && !text(presentation.path(text), false)) {
        problems.add("presentation." + text);
      }
    }
    if (presentation.has("episode")) {
      JsonNode episode = presentation.path("episode");
      String model = episode.path("model").asText();
      boolean known =
          models.stream()
              .anyMatch(
                  m ->
                      m.code().equals(model)
                          && (bound.containsKey(m.startElement())
                              || (m.activityElement() != null
                                  && bound.containsKey(m.activityElement()))));
      if (!known
          || !text(episode.path("dayLabel"), true)
          || (episode.has("historyTitle") && !text(episode.path("historyTitle"), false))) {
        problems.add("presentation.episode");
      }
    }
    if (presentation.has("trends")) {
      JsonNode trends = presentation.path("trends");
      Set<String> offered = new HashSet<>();
      SignalCatalog.forModule(bound, models).forEach(s -> offered.add(s.key()));
      Set<String> seen = new HashSet<>();
      boolean fine = trends.isArray() && trends.size() <= MAX_TRENDS;
      for (JsonNode t : trends) {
        fine &= t.isTextual() && offered.contains(t.asText()) && seen.add(t.asText());
      }
      if (!fine) {
        problems.add("presentation.trends");
      }
    }
    if (presentation.has("quickLog")) {
      Element e = bound.get(presentation.path("quickLog").asText());
      if (e == null || e.valueType() == ValueType.TEXT || e.valueType() == ValueType.DATE) {
        problems.add("presentation.quickLog");
      }
    }
    return problems;
  }

  /** A text has both EN and FR, short, and with {@code {{n}}} when asked. */
  private static boolean text(JsonNode t, boolean needsN) {
    for (String lang : List.of("en", "fr")) {
      String s = t.path(lang).asText("").trim();
      if (s.isEmpty() || s.length() > MAX_TEXT || (needsN && !s.contains("{{n}}"))) {
        return false;
      }
    }
    return true;
  }

  /** Problems with the binding groups, in binding order; empty when they are fine. */
  public static List<String> groupProblems(List<ModuleStore.Binding> bindings) {
    List<String> problems = new ArrayList<>();
    Map<String, JsonNode> labels = new HashMap<>();
    Set<String> closed = new HashSet<>();
    String open = null;
    for (var b : bindings) {
      String g = b.groupCode();
      if (open != null && !open.equals(g)) {
        closed.add(open);
      }
      if (g != null) {
        if (closed.contains(g)
            || b.groupLabel() == null
            || !text(b.groupLabel(), false)
            || (labels.containsKey(g) && !labels.get(g).equals(b.groupLabel()))) {
          problems.add("group:" + g);
          return problems;
        }
        labels.put(g, b.groupLabel());
      }
      open = g;
    }
    return problems;
  }
}
