package care.solace.core.scheduling;

import care.solace.errors.SolaceException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@link Availability}: starts from weekly hours (Slots), less the calendar (bookings with their
 * break, time off) and other people's live holds.
 */
@Service
class Scheduling implements Availability {

  private final WeeklyHoursStore hours;
  private final CalendarStore calendar;
  private final HoldStore holds;

  Scheduling(WeeklyHoursStore hours, CalendarStore calendar, HoldStore holds) {
    this.hours = hours;
    this.calendar = calendar;
    this.holds = holds;
  }

  @Override
  public Optional<String> timeZone(UUID specialistId) {
    return hours.find(specialistId).map(WeeklyHoursStore.Schedule::timeZone);
  }

  @Override
  public boolean hasWeeklyHours(UUID specialistId) {
    return hours.hasWeeklyHours(specialistId);
  }

  @Override
  public Optional<Instant> nextAvailable(UUID specialistId, Instant now, int minutes) {
    return hours
        .find(specialistId)
        .flatMap(
            schedule -> {
              LocalDate today = LocalDate.ofInstant(now, ZoneId.of(schedule.timeZone()));
              return week(specialistId, schedule, today, Slots.HORIZON_DAYS + 1, minutes, now)
                  .slots()
                  .stream()
                  .filter(Slot::available)
                  .map(Slot::start)
                  .findFirst();
            });
  }

  @Override
  public Optional<Week> slots(
      UUID specialistId, LocalDate from, int days, int minutes, Instant now) {
    return hours
        .find(specialistId)
        .map(schedule -> week(specialistId, schedule, from, days, minutes, now));
  }

  @Override
  public Hold hold(UUID specialistId, Instant start, int minutes, String modality, Instant now) {
    var schedule =
        hours.find(specialistId).orElseThrow(() -> new SolaceException(SchedulingError.SLOT_TAKEN));
    LocalDate day = LocalDate.ofInstant(start, ZoneId.of(schedule.timeZone()));
    boolean free =
        week(specialistId, schedule, day, 1, minutes, now).slots().stream()
            .anyMatch(slot -> slot.available() && slot.start().equals(start));
    if (!free) {
      throw new SolaceException(SchedulingError.SLOT_TAKEN);
    }
    var hold =
        new Hold(
            UUID.randomUUID(),
            specialistId,
            start,
            start.plus(Duration.ofMinutes(minutes)),
            modality,
            now.plus(Duration.ofMinutes(HOLD_MINUTES)));
    holds.put(hold);
    return hold;
  }

  @Override
  public Optional<Hold> findHold(UUID holdId) {
    return holds.find(holdId);
  }

  @Override
  public void release(UUID holdId) {
    holds.delete(holdId);
  }

  @Override
  public List<TimeOff> timeOff(UUID specialistId, Instant from, Instant to) {
    return calendar.between(specialistId, from, to).stream()
        .filter(e -> e.kind().equals("TIME_OFF"))
        .map(e -> new TimeOff(e.id(), e.start(), e.end(), e.reason()))
        .toList();
  }

  private Week week(
      UUID specialistId,
      WeeklyHoursStore.Schedule schedule,
      LocalDate from,
      int days,
      int minutes,
      Instant now) {
    var starts = Slots.starts(schedule, from, days, minutes, now);
    if (starts.isEmpty()) {
      return new Week(schedule.timeZone(), List.of());
    }
    Duration breakAfter = Duration.ofMinutes(schedule.breakMinutes());
    Instant first = starts.getFirst().start();
    Instant last = starts.getLast().end().plus(breakAfter);
    var entries = calendar.between(specialistId, first, last);
    var held = holds.forSpecialist(specialistId, now);
    List<Slot> slots =
        starts.stream()
            .map(
                interval -> {
                  // A session needs its break after it free too.
                  Instant end = interval.end().plus(breakAfter);
                  boolean taken =
                      entries.stream()
                              .anyMatch(e -> interval.withEnd(end).overlaps(e.start(), e.end()))
                          || held.stream()
                              .anyMatch(
                                  h ->
                                      interval
                                          .withEnd(end)
                                          .overlaps(h.start(), h.end().plus(breakAfter)));
                  return new Slot(interval.start(), interval.end(), !taken);
                })
            .toList();
    return new Week(schedule.timeZone(), slots);
  }
}
