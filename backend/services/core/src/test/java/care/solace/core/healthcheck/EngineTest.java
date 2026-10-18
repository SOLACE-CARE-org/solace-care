package care.solace.core.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The health check engine on the v1 draft (System Design, Health check; D55). */
class EngineTest {

  private final List<Definition.Question> questions = Canon.v1Questions();

  @Test
  void theV1TestSetsGiveWhatTheCliniciansExpect() {
    for (var set : Canon.v1TestSets()) {
      boolean stop = Engine.safetyStop(questions, set.answers()).isPresent();
      if (set.expected() == null) {
        assertThat(stop).as(set.name()).isTrue();
        continue;
      }
      assertThat(stop).as(set.name()).isFalse();
      var s =
          Engine.suggest(questions, Canon.v1Rules(), Canon.v1SpecialtyOrder(), set.answers(), 1);
      assertThat(s.specialty()).as(set.name()).isEqualTo(set.expected());
    }
  }

  @Test
  void theRestOfAScreenerFollowsOnlyWhenItsFirstTwoItemsReachThree() {
    Map<String, List<String>> answers = new HashMap<>();
    answers.put("gad7_1", List.of("several_days"));
    answers.put("gad7_2", List.of("several_days"));
    assertThat(Engine.next(questions, answers)).doesNotContain("gad7_3");
    answers.put("gad7_2", List.of("more_than_half"));
    assertThat(Engine.next(questions, answers)).contains("gad7_3", "gad7_7");
    assertThat(Engine.next(questions, answers)).doesNotContain("phq9_3");
  }

  @Test
  void anyAnswerButNoToTheSafetyQuestionOrPhq9ItemNineStopsTheCheck() {
    assertThat(Engine.safetyStop(questions, Map.of("safety", List.of("no")))).isEmpty();
    assertThat(Engine.safetyStop(questions, Map.of("safety", List.of("rather_not_say"))))
        .contains("UNKNOWN");
    assertThat(Engine.safetyStop(questions, Map.of("safety", List.of("yes_often"))))
        .contains("SUICIDAL_IDEATION");
    Map<String, List<String>> phq = new HashMap<>();
    phq.put("safety", List.of("no"));
    phq.put("phq9_1", List.of("nearly_every_day"));
    phq.put("phq9_2", List.of("nearly_every_day"));
    phq.put("phq9_9", List.of("several_days"));
    assertThat(Engine.safetyStop(questions, phq)).contains("SUICIDAL_IDEATION");
  }

  @Test
  void totalsUseThePublishedBands() {
    assertThat(Canon.band("PHQ9", 4)).isEqualTo("MINIMAL");
    assertThat(Canon.band("PHQ9", 15)).isEqualTo("MODERATELY_SEVERE");
    assertThat(Canon.band("PHQ9", 20)).isEqualTo("SEVERE");
    assertThat(Canon.band("GAD7", 15)).isEqualTo("SEVERE");
    Map<String, List<String>> answers = new HashMap<>();
    answers.put("gad7_1", List.of("nearly_every_day"));
    answers.put("gad7_2", List.of("nearly_every_day"));
    answers.put("gad7_3", List.of("nearly_every_day"));
    answers.put("gad7_4", List.of("more_than_half"));
    var scores = Engine.scores(questions, answers);
    assertThat(scores).extracting(Engine.Score::screener).containsExactly("GAD7");
    assertThat(scores.get(0).total()).isEqualTo(11);
    assertThat(scores.get(0).band()).isEqualTo("MODERATE");
  }

  @Test
  void aCloseSecondIsAlsoAGoodFitAndTiesFollowTheOrder() {
    Map<String, List<String>> answers =
        Map.of(
            "topics", List.of("worry", "stress"),
            "impact", List.of("most_days"),
            "duration", List.of("two_weeks_to_month"));
    var s = Engine.suggest(questions, Canon.v1Rules(), Canon.v1SpecialtyOrder(), answers, 1);
    // 2 points each: the tie goes to clinical psychology, first in the order.
    assertThat(s.specialty()).isEqualTo("clinical-psychology");
    assertThat(s.alsoFit()).isEqualTo("counselling");
    var none = Engine.suggest(questions, Canon.v1Rules(), Canon.v1SpecialtyOrder(), Map.of(), 1);
    assertThat(none.specialty()).isEqualTo("clinical-psychology");
    assertThat(none.reasons()).isEmpty();
  }

  @Test
  void reasonsNeverNameAConditionAndTheV1RulesPass() {
    assertThat(Versions.wordingAllowed("You said worry affects your work most days.")).isTrue();
    assertThat(Versions.wordingAllowed("You have an anxiety disorder.")).isFalse();
    assertThat(Versions.wordingAllowed("Signes de dépression")).isFalse();
    for (var r : Canon.v1Rules()) {
      assertThat(Versions.wordingAllowed(r.reasonEn())).as(r.code()).isTrue();
      assertThat(Versions.wordingAllowed(r.reasonFr())).as(r.code()).isTrue();
    }
    var v1 =
        new Versions.Version(
            java.util.UUID.randomUUID(),
            1,
            0,
            "DRAFT",
            questions,
            Canon.v1Rules(),
            Canon.v1SpecialtyOrder(),
            Canon.v1TestSets(),
            null,
            "a",
            "A",
            null,
            null,
            null,
            java.time.Instant.now());
    assertThat(
            Versions.problems(
                v1, java.util.Set.of("clinical-psychology", "counselling", "psychiatry")))
        .isEmpty();
  }
}
