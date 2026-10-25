package care.solace.core.scheduling;

import care.solace.core.admin.PlatformSettings;
import care.solace.errors.SolaceException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link Availability}: starts from weekly hours (Slots), less the calendar (bookings with their
 * break, time off) and other people's live holds.
 */
@Service
class Scheduling implements Availability {

  private final PlatformSettings platform;
  private final WeeklyHoursStore hours;
  private final CalendarStore calendar;
  private final HoldStore holds;
  private final TransactionTemplate transactions;

  Scheduling(
      PlatformSettings platform,
      WeeklyHoursStore hours,
      CalendarStore calendar,
      HoldStore holds,
      TransactionTemplate transactions) {
    this.platform = platform;
    this.hours = hours;
    this.calendar = calendar;
    this.holds = holds;
    this.transactions = transactions;
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
              return week(specialistId, schedule, today, horizonDays() + 1, minutes, now)
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
  public Reservation reserve(UUID bookingId, UUID holdId, UUID specialistId) {
    var hold = liveHold(holdId, specialistId);
    Instant end = hold.end().plus(breakOf(specialistId));
    try {
      transactions.executeWithoutResult(
          s -> calendar.insertBooking(bookingId, specialistId, hold.start(), end));
    } catch (DataIntegrityViolationException taken) {
      throw new SolaceException(SchedulingError.SLOT_TAKEN);
    }
    holds.delete(holdId);
    return new Reservation(hold.start(), hold.end(), hold.modality(), hold.expiresAt());
  }

  @Override
  public Reservation move(UUID bookingId, UUID holdId, UUID specialistId) {
    var hold = liveHold(holdId, specialistId);
    Instant end = hold.end().plus(breakOf(specialistId));
    try {
      transactions.executeWithoutResult(
          s -> {
            calendar.deleteBooking(bookingId);
            calendar.insertBooking(bookingId, specialistId, hold.start(), end);
          });
    } catch (DataIntegrityViolationException taken) {
      throw new SolaceException(SchedulingError.SLOT_TAKEN);
    }
    holds.delete(holdId);
    return new Reservation(hold.start(), hold.end(), hold.modality(), hold.expiresAt());
  }

  @Override
  public void releaseBooking(UUID bookingId) {
    calendar.deleteBooking(bookingId);
  }

  private Hold liveHold(UUID holdId, UUID specialistId) {
    return holds
        .find(holdId)
        .filter(h -> h.specialistId().equals(specialistId))
        .orElseThrow(() -> new SolaceException(SchedulingError.SLOT_TAKEN));
  }

  private Duration breakOf(UUID specialistId) {
    return Duration.ofMinutes(
        hours.find(specialistId).map(WeeklyHoursStore.Schedule::breakMinutes).orElse(0));
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
    // Paused: no new times are offered or held; sessions already booked stay.
    if (starts.isEmpty() || !hours.accepting(specialistId)) {
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

  /** How far ahead seekers can book ([14] days, D61). */
  private int horizonDays() {
    return (int)
        platform
            .duration("solace.scheduling.horizon", Duration.ofDays(Slots.HORIZON_DAYS))
            .toDays();
  }
}
