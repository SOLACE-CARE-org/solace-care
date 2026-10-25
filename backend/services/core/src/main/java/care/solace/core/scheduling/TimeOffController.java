package care.solace.core.scheduling;

import care.solace.core.admin.PlatformSettings;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/me/time-off} (contracts/openapi/core.yaml, tag scheduling): Block time off. */
@RestController
class TimeOffController {

  /** Longest single block. Not in the specs: a working default against typing mistakes. */
  static final Duration LONGEST = Duration.ofDays(60);

  private final PlatformSettings platform;
  private final Accounts accounts;
  private final CalendarStore calendar;

  TimeOffController(PlatformSettings platform, Accounts accounts, CalendarStore calendar) {
    this.platform = platform;
    this.accounts = accounts;
    this.calendar = calendar;
  }

  record TimeOffRequest(
      @NotNull Instant start,
      @NotNull Instant end,
      @Pattern(regexp = "PERSONAL|HOLIDAY|OTHER") String reason) {}

  record TimeOff(UUID id, Instant start, Instant end, String reason) {}

  @PostMapping("/v1/me/time-off")
  @ResponseStatus(HttpStatus.CREATED)
  TimeOff block(@AuthenticationPrincipal Jwt token, @Valid @RequestBody TimeOffRequest body) {
    Account specialist = specialist(token);
    if (!body.start().isBefore(body.end())
        || Duration.between(body.start(), body.end())
                .compareTo(platform.duration("solace.scheduling.longest-time-off", LONGEST))
            > 0) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String reason = body.reason() == null ? "PERSONAL" : body.reason();
    UUID id = UUID.randomUUID();
    try {
      calendar.insertTimeOff(id, specialist.id(), body.start(), body.end(), reason);
    } catch (DataIntegrityViolationException overlap) {
      throw new SolaceException(SchedulingError.TIME_OFF_CONFLICT);
    }
    return new TimeOff(id, body.start(), body.end(), reason);
  }

  @DeleteMapping("/v1/me/time-off/{timeOffId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@AuthenticationPrincipal Jwt token, @PathVariable UUID timeOffId) {
    calendar.deleteTimeOff(timeOffId, specialist(token).id());
  }

  private Account specialist(Jwt token) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(SchedulingError.NOT_A_SPECIALIST);
    }
    return account;
  }
}
