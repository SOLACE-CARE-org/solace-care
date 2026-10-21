package care.solace.core.scheduling;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/me/weekly-hours} (contracts/openapi/core.yaml, tag scheduling). */
@RestController
class WeeklyHoursController {

  /** Prefilled until the specialist saves: Cameroon, and the values SpecAvailability shows. */
  private static final String DEFAULT_TIME_ZONE = "Africa/Douala";

  private static final int DEFAULT_BREAK_MINUTES = 10;
  private static final int DEFAULT_MINIMUM_NOTICE_HOURS = 2;

  private final Accounts accounts;
  private final WeeklyHoursStore store;
  private final TransactionTemplate transactions;
  private final Clock clock;

  WeeklyHoursController(
      Accounts accounts, WeeklyHoursStore store, TransactionTemplate transactions, Clock clock) {
    this.accounts = accounts;
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
  }

  record WeeklyHours(
      @NotNull @Size(max = 64) String timeZone,
      @NotNull @Size(max = 7) List<@NotNull @Valid Day> days,
      @NotNull Integer breakMinutes,
      @NotNull Integer minimumNoticeHours) {}

  record Day(
      @NotNull DayOfWeek weekday, @NotNull @Size(max = 6) List<@NotNull @Valid Range> ranges) {}

  record Range(
      @NotNull @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$") String start,
      @NotNull @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$") String end) {}

  @GetMapping("/v1/me/weekly-hours")
  WeeklyHours get(@AuthenticationPrincipal Jwt token) {
    Account specialist = specialist(token);
    return store
        .find(specialist.id())
        .map(WeeklyHoursController::view)
        .orElseGet(
            () ->
                view(
                    new WeeklyHoursStore.Schedule(
                        DEFAULT_TIME_ZONE,
                        DEFAULT_BREAK_MINUTES,
                        DEFAULT_MINIMUM_NOTICE_HOURS,
                        List.of())));
  }

  @PutMapping("/v1/me/weekly-hours")
  WeeklyHours save(@AuthenticationPrincipal Jwt token, @Valid @RequestBody WeeklyHours body) {
    Account specialist = specialist(token);
    try {
      ZoneId.of(body.timeZone());
    } catch (DateTimeException e) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (!Set.of(0, 10, 15).contains(body.breakMinutes())
        || !Set.of(2, 12, 24).contains(body.minimumNoticeHours())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    List<WeeklyHoursStore.Rule> rules = new ArrayList<>();
    Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
    for (Day day : body.days()) {
      if (!seen.add(day.weekday())) {
        throw new SolaceException(SchedulingError.OVERLAPPING_HOURS);
      }
      List<WeeklyHoursStore.Rule> onDay =
          day.ranges().stream()
              .map(
                  r ->
                      new WeeklyHoursStore.Rule(
                          day.weekday(), LocalTime.parse(r.start()), LocalTime.parse(r.end())))
              .sorted(Comparator.comparing(WeeklyHoursStore.Rule::start))
              .toList();
      for (int i = 0; i < onDay.size(); i++) {
        var rule = onDay.get(i);
        if (!rule.start().isBefore(rule.end())) {
          throw new SolaceException(CommonError.REQUEST_INVALID);
        }
        if (i > 0 && rule.start().isBefore(onDay.get(i - 1).end())) {
          throw new SolaceException(SchedulingError.OVERLAPPING_HOURS);
        }
      }
      rules.addAll(onDay);
    }
    var schedule =
        new WeeklyHoursStore.Schedule(
            body.timeZone(), body.breakMinutes(), body.minimumNoticeHours(), rules);
    transactions.executeWithoutResult(s -> store.save(specialist.id(), schedule, clock.instant()));
    return get(token);
  }

  record AcceptingBookings(@NotNull Boolean accepting) {}

  @GetMapping("/v1/me/accepting-bookings")
  AcceptingBookings accepting(@AuthenticationPrincipal Jwt token) {
    return new AcceptingBookings(store.accepting(specialist(token).id()));
  }

  /** Pauses or resumes new bookings; needs weekly hours first. */
  @PutMapping("/v1/me/accepting-bookings")
  AcceptingBookings setAccepting(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody AcceptingBookings body) {
    Account specialist = specialist(token);
    if (!store.setAccepting(specialist.id(), body.accepting())) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    return accepting(token);
  }

  private Account specialist(Jwt token) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(SchedulingError.NOT_A_SPECIALIST);
    }
    return account;
  }

  /** Every day of the week, Monday first, with its ranges (empty when unavailable). */
  private static WeeklyHours view(WeeklyHoursStore.Schedule schedule) {
    List<Day> days =
        Arrays.stream(DayOfWeek.values())
            .map(
                weekday ->
                    new Day(
                        weekday,
                        schedule.rules().stream()
                            .filter(r -> r.weekday() == weekday)
                            .map(r -> new Range(r.start().toString(), r.end().toString()))
                            .toList()))
            .toList();
    return new WeeklyHours(
        schedule.timeZone(), days, schedule.breakMinutes(), schedule.minimumNoticeHours());
  }
}
