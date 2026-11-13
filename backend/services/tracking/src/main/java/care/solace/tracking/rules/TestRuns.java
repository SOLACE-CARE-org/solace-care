package care.solace.tracking.rules;

import care.solace.tracking.episodes.EpisodeModels;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Rules against every made-up history (Tracking Service Spec, Testing before publishing; D61): what
 * the builder shows, and the check that each rule has a history that fires it and one that does not
 * before its version can be submitted. Real patient data is never used.
 */
@Component
public class TestRuns {

  /** A rule as the test run reads it. */
  public record Rule(String code, JsonNode conditions, JsonNode unless) {}

  /**
   * One rule on one history.
   *
   * @param blockedByUnless the conditions held but an answer switched the rule off
   */
  public record Run(
      String history,
      JsonNode historyLabels,
      String rule,
      boolean fires,
      boolean blockedByUnless,
      List<RuleEngine.ConditionResult> conditions) {}

  private final HistoryLibrary histories;
  private final EpisodeModels models;
  private final Clock clock;

  TestRuns(HistoryLibrary histories, EpisodeModels models, Clock clock) {
    this.histories = histories;
    this.models = models;
    this.clock = clock;
  }

  public List<Run> run(List<Rule> rules) {
    LocalDate today = LocalDate.now(clock);
    List<Run> out = new ArrayList<>();
    for (var stored : histories.all()) {
      var history =
          SyntheticHistories.build(stored.code(), stored.recipe(), stored.context(), today);
      var signals = history.signals(today, models.all());
      for (var r : rules) {
        var result = RuleEngine.evaluate(r.conditions(), signals);
        boolean unless = Unless.blocks(r.unless(), history.answers(), null);
        out.add(
            new Run(
                stored.code(),
                stored.labels(),
                r.code(),
                result.held() && !unless,
                result.held() && unless,
                result.conditions()));
      }
    }
    return out;
  }

  /** Rules no history fires, or that every history fires; empty when each is tested both ways. */
  public Set<String> untested(List<Rule> rules) {
    Set<String> fires = new LinkedHashSet<>();
    Set<String> quiet = new LinkedHashSet<>();
    for (var run : run(rules)) {
      (run.fires() ? fires : quiet).add(run.rule());
    }
    Set<String> untested = new LinkedHashSet<>();
    for (var r : rules) {
      if (!fires.contains(r.code()) || !quiet.contains(r.code())) {
        untested.add(r.code());
      }
    }
    return untested;
  }
}
