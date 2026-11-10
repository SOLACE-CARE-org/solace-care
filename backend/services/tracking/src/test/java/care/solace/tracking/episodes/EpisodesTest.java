package care.solace.tracking.episodes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The episode algorithm's rules on made-up histories (D50, D61). The cycle cases are the launch
 * cycle model's own tests, read through the menstrual_cycle v1 definition: they must not change.
 */
class EpisodesTest {

  private static final LocalDate D = LocalDate.of(2026, 7, 1);
  private static final EpisodeModel CYCLE = TestModels.menstrualCycle();

  /** Flow on {@code days} days from {@code from}, with the given heaviest day count. */
  private static void period(Map<LocalDate, String> flow, LocalDate from, int days, int veryHeavy) {
    for (int i = 0; i < days; i++) {
      flow.put(from.plusDays(i), i < veryHeavy ? "very_heavy" : "medium");
    }
  }

  @Test
  void flowAfterAGapStartsACycleAndTheNewestNeverCounts() {
    Map<LocalDate, String> flow = new HashMap<>();
    period(flow, D, 5, 0);
    period(flow, D.plusDays(41), 6, 3);
    period(flow, D.plusDays(80), 4, 0);
    flow.put(D.plusDays(20), "none");

    var cycles = Episodes.find(CYCLE, flow, new TreeSet<>(), new TreeSet<>());

    assertThat(cycles).hasSize(3);
    assertThat(cycles.get(0).metric("length_days")).isEqualTo(41);
    assertThat(cycles.get(0).metric("period_days")).isEqualTo(5);
    assertThat(cycles.get(0).end()).isEqualTo(D.plusDays(40));
    assertThat(cycles.get(1).metric("length_days")).isEqualTo(39);
    assertThat(cycles.get(1).metric("very_heavy_days")).isEqualTo(3);
    assertThat(cycles.get(2).complete()).isFalse();
    assertThat(cycles.get(2).metric("length_days")).isNull();
    assertThat(cycles.get(2).end()).isNull();
  }

  @Test
  void loggedStartsAndEndsWinOverInference() {
    Map<LocalDate, String> flow = new HashMap<>();
    period(flow, D.plusDays(1), 4, 0);
    var starts = new TreeSet<LocalDate>();
    starts.add(D);
    starts.add(D.plusDays(2)); // pressed twice in one period
    starts.add(D.plusDays(30));
    var ends = new TreeSet<LocalDate>();
    ends.add(D.plusDays(7));

    var cycles = Episodes.find(CYCLE, flow, starts, ends);

    assertThat(cycles).hasSize(2);
    assertThat(cycles.get(0).start()).isEqualTo(D);
    assertThat(cycles.get(0).metric("period_days")).isEqualTo(8);
    assertThat(cycles.get(0).metric("length_days")).isEqualTo(30);
  }

  @Test
  void oneUnloggedDayDoesNotEndAPeriodButNoneDoes() {
    Map<LocalDate, String> flow = new HashMap<>();
    flow.put(D, "heavy");
    flow.put(D.plusDays(1), "medium");
    // D+2 not logged
    flow.put(D.plusDays(3), "light");
    flow.put(D.plusDays(4), "none");
    flow.put(D.plusDays(5), "light");
    period(flow, D.plusDays(35), 3, 0);

    var cycles = Episodes.find(CYCLE, flow, new TreeSet<>(), new TreeSet<>());

    assertThat(cycles.get(0).metric("period_days")).isEqualTo(4);
  }

  @Test
  void anotherModelFindsAttacksThatLastUntilTheirRunEnds() {
    var migraine = TestModels.migraine();
    Map<LocalDate, String> headache = new HashMap<>();
    headache.put(D, "true");
    headache.put(D.plusDays(1), "true");
    headache.put(D.plusDays(2), "false");
    headache.put(D.plusDays(10), "true");
    headache.put(D.plusDays(20), "true");

    var attacks = Episodes.find(migraine, headache, new TreeSet<>(), new TreeSet<>());

    assertThat(attacks)
        .extracting(Episode::start)
        .containsExactly(D, D.plusDays(10), D.plusDays(20));
    assertThat(attacks.get(0).end()).isEqualTo(D.plusDays(1));
    assertThat(attacks.get(0).metric("length_days")).isEqualTo(2);
    assertThat(attacks.get(0).metric("headache_days")).isEqualTo(2);
    assertThat(attacks.get(1).metric("length_days")).isEqualTo(1);
    // The newest never counts, even when its run has ended.
    assertThat(attacks.get(2).complete()).isFalse();
    assertThat(attacks.get(2).metric("length_days")).isNull();
  }
}
