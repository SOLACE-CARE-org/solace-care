package care.solace.tracking.rules;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.episodes.EpisodeModel;
import care.solace.tracking.episodes.TestModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The launch rules from the Tracking Service Spec, with its bracketed placeholder thresholds,
 * against the synthetic histories: which fire, and which "unless" switches off (D51).
 */
class LaunchRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
  private static final List<EpisodeModel> MODELS = TestModels.launch().all();

  static final Map<String, String> CONDITIONS =
      Map.of(
          "pcos.long_cycles",
          "[{\"signal\":\"menstrual_cycle.length_days\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gt\",\"threshold\":35,\"count\":3}]",
          "pcos.long_periods",
          "[{\"signal\":\"menstrual_cycle.period_days\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gte\",\"threshold\":8,\"count\":2}]",
          "pcos.no_period",
          "[{\"signal\":\"daily_value\",\"element\":\"flow_intensity\",\"operator\":\"days_in_window\",\"comparison\":\"in\",\"threshold\":[\"light\",\"medium\",\"heavy\",\"very_heavy\"],\"mode\":\"at_most\",\"count\":0,\"window\":90,\"minData\":30}]",
          "pcos.heavy_flow",
          "[{\"signal\":\"menstrual_cycle.very_heavy_days\",\"operator\":\"consecutive_episodes\",\"comparison\":\"gte\",\"threshold\":3,\"count\":2}]",
          "checkin.low_mood",
          "[{\"signal\":\"daily_value\",\"element\":\"mood\",\"operator\":\"days_in_window\",\"comparison\":\"lte\",\"threshold\":2,\"mode\":\"at_least\",\"count\":10,\"window\":14,\"minData\":10}]",
          "checkin.short_sleep",
          "[{\"signal\":\"daily_value\",\"element\":\"sleep_minutes\",\"operator\":\"average_in_window\",\"comparison\":\"lt\",\"threshold\":300,\"window\":14,\"minData\":10}]");

  static final Map<String, String> UNLESS =
      Map.of(
          "pcos.long_cycles", "[{\"question\":\"hormonal_treatment\",\"equals\":true}]",
          "pcos.no_period",
              "[{\"question\":\"trying_to_conceive\",\"equals\":true},{\"question\":\"hormonal_treatment\",\"equals\":true}]");

  private Map<String, Set<String>> fired() throws Exception {
    Map<String, Set<String>> fired = new LinkedHashMap<>();
    for (var history : SyntheticHistories.all(TODAY)) {
      var signals = history.signals(TODAY, MODELS);
      for (var rule : CONDITIONS.entrySet()) {
        JsonNode conditions = JSON.readTree(rule.getValue());
        JsonNode unless = JSON.readTree(UNLESS.getOrDefault(rule.getKey(), "[]"));
        if (RuleEngine.evaluate(conditions, signals).held()
            && !Unless.blocks(unless, history.answers(), null)) {
          fired.computeIfAbsent(rule.getKey(), k -> new TreeSet<>()).add(history.code());
        }
      }
    }
    return fired;
  }

  @Test
  void eachLaunchRuleFiresOnlyOnTheHistoryItDescribes() throws Exception {
    var fired = fired();
    assertThat(fired.get("pcos.long_cycles")).containsExactly("long_cycles");
    assertThat(fired.get("pcos.long_periods")).containsExactly("long_periods");
    assertThat(fired.get("pcos.no_period")).containsExactly("no_period_90_days");
    assertThat(fired.get("pcos.heavy_flow")).containsExactly("heavy_flow");
    assertThat(fired.get("checkin.low_mood")).containsExactly("low_mood_two_weeks");
    assertThat(fired.get("checkin.short_sleep")).containsExactly("short_sleep");
  }

  @Test
  void theEvidenceNamesTheExactCyclesUsed() throws Exception {
    var history =
        SyntheticHistories.all(TODAY).stream()
            .filter(h -> h.code().equals("long_cycles"))
            .findFirst()
            .orElseThrow();
    var result =
        RuleEngine.evaluate(
            JSON.readTree(CONDITIONS.get("pcos.long_cycles")), history.signals(TODAY, MODELS));
    assertThat(result.held()).isTrue();
    assertThat(result.evidence().get("n")).isEqualTo(3);
    assertThat((java.util.List<?>) result.evidence().get("episodes")).hasSize(3);
  }

  @Test
  void theWordListBlocksDiagnosticWording() {
    assertThat(RuleRules.wordingAllowed("Your last {n} cycles were longer than {threshold} days."))
        .isTrue();
    assertThat(RuleRules.wordingAllowed("This is not a diagnosis.")).isTrue();
    assertThat(RuleRules.wordingAllowed("You may have PCOS.")).isFalse();
    assertThat(RuleRules.wordingAllowed("Signs of depression")).isFalse();
    assertThat(RuleRules.wordingAllowed("Un trouble du cycle")).isFalse();
    assertThat(RuleRules.wordingAllowed("Sévère")).isFalse();
  }
}
