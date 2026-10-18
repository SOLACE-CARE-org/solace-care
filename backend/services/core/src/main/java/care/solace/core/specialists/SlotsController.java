package care.solace.core.specialists;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.core.scheduling.Availability;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Choose a session and the specialist's calendar (contracts/openapi/core.yaml, tag scheduling).
 * Session lengths live with the specialist's offerings, so these endpoints sit here and ask
 * scheduling for the starts.
 */
@RestController
class SlotsController {

  private final DirectoryStore directory;
  private final ApplicationStore applications;
  private final Availability availability;
  private final Accounts accounts;
  private final Clock clock;

  SlotsController(
      DirectoryStore directory,
      ApplicationStore applications,
      Availability availability,
      Accounts accounts,
      Clock clock) {
    this.directory = directory;
    this.applications = applications;
    this.availability = availability;
    this.accounts = accounts;
    this.clock = clock;
  }

  record Slot(Instant start, boolean available) {}

  record Slots(String timeZone, int durationMinutes, long price, List<Slot> slots) {}

  record HoldRequest(
      @NotNull UUID specialistId,
      @NotNull Instant start,
      @NotNull @Pattern(regexp = "AUDIO|VIDEO") String modality) {}

  record SlotHold(
      UUID holdId,
      UUID specialistId,
      Instant start,
      Instant end,
      String modality,
      Instant expiresAt) {}

  record TimeOff(UUID id, Instant start, Instant end, String reason) {}

  record Day(LocalDate date, List<Instant> openStarts, List<TimeOff> timeOff) {}

  record Calendar(String timeZone, List<Day> days) {}

  @GetMapping("/v1/catalogue/specialists/{id}/slots")
  Slots slots(
      @PathVariable UUID id,
      @RequestParam String modality,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(defaultValue = "7") int days) {
    if (days < 1 || days > 14) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var offer = offer(id, modality);
    Instant now = clock.instant();
    LocalDate first = from != null ? from : today(id, now);
    var week =
        availability
            .slots(id, first, days, offer.durationMinutes(), now)
            .orElse(new Availability.Week("Africa/Douala", List.of()));
    return new Slots(
        week.timeZone(),
        offer.durationMinutes(),
        offer.price(),
        week.slots().stream().map(s -> new Slot(s.start(), s.available())).toList());
  }

  @PostMapping("/v1/slot-holds")
  @ResponseStatus(HttpStatus.CREATED)
  SlotHold hold(@Valid @RequestBody HoldRequest body) {
    var offer = offer(body.specialistId(), body.modality());
    var hold =
        availability.hold(
            body.specialistId(),
            body.start(),
            offer.durationMinutes(),
            body.modality(),
            clock.instant());
    return new SlotHold(
        hold.id(),
        hold.specialistId(),
        hold.start(),
        hold.end(),
        hold.modality(),
        hold.expiresAt());
  }

  @DeleteMapping("/v1/slot-holds/{holdId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void release(@PathVariable UUID holdId) {
    availability.release(holdId);
  }

  /** SpecCalendar: open starts for the shortest session offered, and time off, day by day. */
  @GetMapping("/v1/me/calendar")
  Calendar calendar(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(defaultValue = "7") int days) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(SpecialistError.NOT_A_SPECIALIST);
    }
    if (days < 1 || days > 14) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    UUID id = account.id();
    Instant now = clock.instant();
    String timeZone = availability.timeZone(id).orElse("Africa/Douala");
    ZoneId zone = ZoneId.of(timeZone);
    LocalDate first = from != null ? from : LocalDate.ofInstant(now, zone);
    int shortest =
        applications.offerings(id).stream()
            .filter(ApplicationStore.Offering::enabled)
            .mapToInt(ApplicationStore.Offering::durationMinutes)
            .min()
            .orElse(0);
    var starts =
        availability
            .slots(id, first, days, shortest, now)
            .map(Availability.Week::slots)
            .orElse(List.of());
    var off =
        availability.timeOff(
            id,
            first.atStartOfDay(zone).toInstant(),
            first.plusDays(days).atStartOfDay(zone).toInstant());
    List<Day> result = new ArrayList<>();
    for (int d = 0; d < days; d++) {
      LocalDate date = first.plusDays(d);
      Instant dayStart = date.atStartOfDay(zone).toInstant();
      Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
      result.add(
          new Day(
              date,
              starts.stream()
                  .filter(
                      s ->
                          s.available()
                              && !s.start().isBefore(dayStart)
                              && s.start().isBefore(dayEnd))
                  .map(Availability.Slot::start)
                  .toList(),
              off.stream()
                  .filter(t -> t.start().isBefore(dayEnd) && dayStart.isBefore(t.end()))
                  .sorted(Comparator.comparing(Availability.TimeOff::start))
                  .map(t -> new TimeOff(t.id(), t.start(), t.end(), t.reason()))
                  .toList()));
    }
    return new Calendar(timeZone, result);
  }

  /** A listed specialist's enabled session in this format, or 404. */
  private DirectoryStore.Offer offer(UUID specialistId, String modality) {
    if (!"AUDIO".equals(modality) && !"VIDEO".equals(modality)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    directory.listed(specialistId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return directory.offers(specialistId).stream()
        .filter(o -> o.modality().equals(modality))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private LocalDate today(UUID specialistId, Instant now) {
    return LocalDate.ofInstant(
        now, ZoneId.of(availability.timeZone(specialistId).orElse("Africa/Douala")));
  }
}
