package care.solace.tracking.cycles;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The cycle model's rules, on made-up histories (D50). */
class CycleModelTest {

  private static final LocalDate D = LocalDate.of(2026, 7, 1);

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

    var cycles = CycleModel.cycles(flow, new TreeSet<>(), new TreeSet<>(), 10);

    assertThat(cycles).hasSize(3);
    assertThat(cycles.get(0).lengthDays()).isEqualTo(41);
    assertThat(cycles.get(0).periodDays()).isEqualTo(5);
    assertThat(cycles.get(1).lengthDays()).isEqualTo(39);
    assertThat(cycles.get(1).veryHeavyDays()).isEqualTo(3);
    assertThat(cycles.get(2).complete()).isFalse();
    assertThat(cycles.get(2).lengthDays()).isNull();
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

    var cycles = CycleModel.cycles(flow, starts, ends, 10);

    assertThat(cycles).hasSize(2);
    assertThat(cycles.get(0).start()).isEqualTo(D);
    assertThat(cycles.get(0).periodDays()).isEqualTo(8);
    assertThat(cycles.get(0).lengthDays()).isEqualTo(30);
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

    var cycles = CycleModel.cycles(flow, new TreeSet<>(), new TreeSet<>(), 10);

    assertThat(cycles.get(0).periodDays()).isEqualTo(4);
  }
}
