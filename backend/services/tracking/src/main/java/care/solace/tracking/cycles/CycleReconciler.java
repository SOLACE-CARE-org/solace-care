package care.solace.tracking.cycles;

import care.solace.tracking.PersonalData;
import care.solace.tracking.sync.Sync;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rebuilds a person's cycles after every push or delete that touches a cycle element, in the same
 * transaction and before the pattern check (Tracking Service Spec, How the pattern check runs).
 */
@Component
public class CycleReconciler implements PersonalData {

  static final Set<String> CYCLE_ELEMENTS = Set.of("flow_intensity", "period_start", "period_end");

  private final JdbcClient db;
  private final Sync sync;
  private final int gapDays;
  private final Clock clock;

  CycleReconciler(
      JdbcClient db,
      Sync sync,
      @Value("${solace.tracking.cycle.new-period-gap-days:10}") int gapDays,
      Clock clock) {
    this.db = db;
    this.sync = sync;
    this.gapDays = gapDays;
    this.clock = clock;
  }

  @EventListener
  @Order(1)
  void on(Sync.EntriesChanged changed) {
    if (changed.elementCodes().stream().anyMatch(CYCLE_ELEMENTS::contains)) {
      rebuild(changed.userId());
    }
  }

  public List<CycleModel.Cycle> rebuild(UUID user) {
    Map<LocalDate, String> flow = new HashMap<>();
    SortedSet<LocalDate> starts = new TreeSet<>();
    SortedSet<LocalDate> ends = new TreeSet<>();
    for (var o : sync.live(user, CYCLE_ELEMENTS, LocalDate.of(2000, 1, 1))) {
      switch (o.elementCode()) {
        case "flow_intensity" -> flow.put(o.observedOn(), o.value().text());
        case "period_start" ->
            starts.add(o.value().date() != null ? o.value().date() : o.observedOn());
        case "period_end" -> ends.add(o.value().date() != null ? o.value().date() : o.observedOn());
        default -> {}
      }
    }
    var cycles = CycleModel.cycles(flow, starts, ends, gapDays);
    db.sql("DELETE FROM cycle WHERE user_id = :u").param("u", user).update();
    for (var c : cycles) {
      db.sql(
              """
              INSERT INTO cycle (user_id, start_date, end_date, period_end_date, length_days,
                                 period_days, very_heavy_days, complete)
              VALUES (:u, :s, :e, :pe, :l, :pd, :vh, :c)
              """)
          .param("u", user)
          .param("s", Date.valueOf(c.start()))
          .param("e", c.end() == null ? null : Date.valueOf(c.end()))
          .param("pe", c.periodEnd() == null ? null : Date.valueOf(c.periodEnd()))
          .param("l", c.lengthDays())
          .param("pd", c.periodDays())
          .param("vh", c.veryHeavyDays())
          .param("c", c.complete())
          .update();
    }
    return cycles;
  }

  /** Stored cycles, oldest first. */
  public List<CycleModel.Cycle> cycles(UUID user) {
    return db.sql("SELECT * FROM cycle WHERE user_id = :u ORDER BY start_date")
        .param("u", user)
        .query(
            (rs, n) -> {
              Date end = rs.getDate("end_date");
              Date periodEnd = rs.getDate("period_end_date");
              return new CycleModel.Cycle(
                  rs.getDate("start_date").toLocalDate(),
                  end == null ? null : end.toLocalDate(),
                  periodEnd == null ? null : periodEnd.toLocalDate(),
                  (Integer) rs.getObject("length_days"),
                  (Integer) rs.getObject("period_days"),
                  rs.getInt("very_heavy_days"),
                  rs.getBoolean("complete"));
            })
        .list();
  }

  /** Day of the current cycle, counting its first day as 1, on the person's local day. */
  public Optional<Integer> cycleDay(UUID user, ZoneId zone) {
    var all = cycles(user);
    if (all.isEmpty()) {
      return Optional.empty();
    }
    LocalDate today = LocalDate.now(clock.withZone(zone));
    var latest = all.get(all.size() - 1);
    long day = ChronoUnit.DAYS.between(latest.start(), today) + 1;
    return day < 1 ? Optional.empty() : Optional.of((int) day);
  }

  @Override
  public void erase(UUID userId) {
    db.sql("DELETE FROM cycle WHERE user_id = :u").param("u", userId).update();
  }
}
