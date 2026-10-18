package care.solace.core.healthcheck;

import care.solace.core.healthcheck.Definition.Kind;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Definition.Rule;
import care.solace.core.healthcheck.Definition.ShowIf;
import care.solace.core.healthcheck.Definition.When;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns answers into what to ask next, the safety stop, screener totals and a suggestion (System
 * Design, Health check, How answers become a recommendation). Pure and deterministic: no AI, no
 * clock, no database, so test runs and real responses give the same result.
 */
final class Engine {

  private Engine() {}

  record Score(String screener, int total, String band) {}

  record Suggestion(
      String specialty,
      String alsoFit,
      List<Rule> reasons,
      List<Rule> alsoFitReasons,
      Map<String, Integer> points) {}

  /** Questions to show, in order, given what was answered so far. */
  static List<Question> visible(List<Question> questions, Map<String, List<String>> answers) {
    List<Question> shown = new ArrayList<>();
    for (Question q : questions) {
      if (shows(q.showIf(), questions, answers)) {
        shown.add(q);
      }
    }
    return shown;
  }

  /** Codes still to answer, in order. */
  static List<String> next(List<Question> questions, Map<String, List<String>> answers) {
    return visible(questions, answers).stream()
        .map(Question::code)
        .filter(code -> answers.getOrDefault(code, List.of()).isEmpty())
        .toList();
  }

  private static boolean shows(
      ShowIf showIf, List<Question> questions, Map<String, List<String>> answers) {
    if (showIf == null) {
      return true;
    }
    if (showIf.question() != null) {
      List<String> chosen = answers.getOrDefault(showIf.question(), List.of());
      return showIf.anyOf() != null && chosen.stream().anyMatch(showIf.anyOf()::contains);
    }
    if (showIf.screener() != null) {
      int first = showIf.firstItems() == null ? 2 : showIf.firstItems();
      Integer total = partial(questions, answers, showIf.screener(), first);
      return total != null && total >= (showIf.atLeast() == null ? 0 : showIf.atLeast());
    }
    return true;
  }

  /** The total of a screener's first items, or null until they are all answered. */
  private static Integer partial(
      List<Question> questions, Map<String, List<String>> answers, String screener, int first) {
    int total = 0;
    for (Question q : questions) {
      if (q.kind() == Kind.SCREENER && screener.equals(q.screener()) && q.item() <= first) {
        Integer v = value(q, answers);
        if (v == null) {
          return null;
        }
        total += v;
      }
    }
    return total;
  }

  private static Integer value(Question q, Map<String, List<String>> answers) {
    List<String> chosen = answers.getOrDefault(q.code(), List.of());
    if (chosen.isEmpty()) {
      return null;
    }
    return q.options().stream()
        .filter(o -> o.code().equals(chosen.get(0)))
        .map(Definition.Option::value)
        .findFirst()
        .orElse(null);
  }

  /**
   * The locked safety rule: any answer but "no" to the safety question, or PHQ-9 item 9 above 0.
   * Returns the crisis category, or empty when nothing stops the check.
   */
  static Optional<String> safetyStop(List<Question> questions, Map<String, List<String>> answers) {
    List<String> safety = answers.getOrDefault(Canon.SAFETY, List.of());
    if (!safety.isEmpty() && !safety.contains("no")) {
      return Optional.of(safety.contains("rather_not_say") ? "UNKNOWN" : "SUICIDAL_IDEATION");
    }
    for (Question q : questions) {
      if (q.kind() == Kind.SCREENER && Canon.PHQ9.equals(q.screener()) && q.item() == 9) {
        Integer v = value(q, answers);
        if (v != null && v > 0) {
          return Optional.of("SUICIDAL_IDEATION");
        }
      }
    }
    return Optional.empty();
  }

  /** Totals and published bands of every screener whose first two items were answered. */
  static List<Score> scores(List<Question> questions, Map<String, List<String>> answers) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (String screener : List.of(Canon.PHQ9, Canon.GAD7)) {
      if (partial(questions, answers, screener, 2) == null) {
        continue;
      }
      int total = 0;
      for (Question q : visible(questions, answers)) {
        if (q.kind() == Kind.SCREENER && screener.equals(q.screener())) {
          Integer v = value(q, answers);
          total += v == null ? 0 : v;
        }
      }
      totals.put(screener, total);
    }
    return totals.entrySet().stream()
        .map(e -> new Score(e.getKey(), e.getValue(), Canon.band(e.getKey(), e.getValue())))
        .toList();
  }

  /**
   * Adds each rule's points to its specialty. The most points wins; ties follow the specialty
   * order; a second specialty within {@code alsoFitWithin} points is "also a good fit". With no
   * points at all, the first specialty in the order is suggested with no reasons.
   */
  static Suggestion suggest(
      List<Question> questions,
      List<Rule> rules,
      List<String> order,
      Map<String, List<String>> answers,
      int alsoFitWithin) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (Score s : scores(questions, answers)) {
      totals.put(s.screener(), s.total());
    }
    Map<String, Integer> points = new LinkedHashMap<>();
    Map<String, List<Rule>> why = new LinkedHashMap<>();
    for (Rule r : rules) {
      if (r.when().stream().allMatch(w -> holds(w, answers, totals))) {
        points.merge(r.specialty(), r.points(), Integer::sum);
        why.computeIfAbsent(r.specialty(), k -> new ArrayList<>()).add(r);
      }
    }
    List<String> ranked = new ArrayList<>(points.keySet());
    ranked.sort(
        (a, b) -> {
          int byPoints = Integer.compare(points.get(b), points.get(a));
          return byPoints != 0 ? byPoints : Integer.compare(rank(order, a), rank(order, b));
        });
    if (ranked.isEmpty()) {
      return new Suggestion(
          order.isEmpty() ? null : order.get(0), null, List.of(), List.of(), points);
    }
    String top = ranked.get(0);
    String second = null;
    if (ranked.size() > 1 && points.get(top) - points.get(ranked.get(1)) <= alsoFitWithin) {
      second = ranked.get(1);
    }
    return new Suggestion(
        top,
        second,
        why.getOrDefault(top, List.of()),
        second == null ? List.of() : why.getOrDefault(second, List.of()),
        points);
  }

  private static int rank(List<String> order, String specialty) {
    int i = order.indexOf(specialty);
    return i < 0 ? Integer.MAX_VALUE : i;
  }

  private static boolean holds(
      When w, Map<String, List<String>> answers, Map<String, Integer> totals) {
    if (w.question() != null) {
      List<String> chosen = answers.getOrDefault(w.question(), List.of());
      return w.anyOf() != null && chosen.stream().anyMatch(w.anyOf()::contains);
    }
    if (w.screener() != null) {
      Integer total = totals.get(w.screener());
      return total != null
          && total >= (w.atLeast() == null ? 0 : w.atLeast())
          && (w.atMost() == null || total <= w.atMost());
    }
    return false;
  }
}
