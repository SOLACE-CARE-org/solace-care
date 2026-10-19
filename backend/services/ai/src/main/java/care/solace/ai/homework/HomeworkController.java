package care.solace.ai.homework;

import care.solace.ai.admin.Members;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Homework and the companion (contracts/openapi/ai.yaml, tag homework; D57). */
@RestController
class HomeworkController {

  record Assign(
      @NotNull String exercise,
      @Size(max = 300) String customTask,
      @NotNull String frequency,
      Integer times,
      @Size(max = 500) String note,
      @NotNull Boolean companionGuided,
      Instant dueBy) {}

  record Answer(@NotNull @Size(min = 1, max = 2000) String answer) {}

  private final Members members;
  private final Homeworks homeworks;
  private final Companion companion;

  HomeworkController(Members members, Homeworks homeworks, Companion companion) {
    this.members = members;
    this.homeworks = homeworks;
    this.companion = companion;
  }

  @PostMapping("/v1/specialist/bookings/{bookingId}/homework")
  @ResponseStatus(HttpStatus.CREATED)
  Homeworks.Homework assign(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody Assign a) {
    return homeworks.assign(
        bookingId,
        members.specialist(token),
        new Homeworks.Assignment(
            a.exercise(),
            a.customTask(),
            a.frequency(),
            a.times(),
            a.note(),
            a.companionGuided(),
            a.dueBy()));
  }

  @GetMapping("/v1/specialist/bookings/{bookingId}/homework")
  List<Homeworks.Homework> forBooking(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
    return homeworks.forBooking(bookingId, members.specialist(token));
  }

  @GetMapping("/v1/me/homework")
  List<Homeworks.Homework> mine(@AuthenticationPrincipal Jwt token) {
    return homeworks.mine(members.seeker(token));
  }

  @GetMapping("/v1/me/homework/{homeworkId}/companion")
  Companion.Turn state(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID homeworkId, Locale locale) {
    return companion.state(homeworkId, members.seeker(token), locale);
  }

  @PostMapping("/v1/me/homework/{homeworkId}/companion")
  Companion.Turn answer(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID homeworkId,
      @Valid @RequestBody Answer body,
      Locale locale) {
    return companion.answer(homeworkId, members.seeker(token), body.answer(), locale);
  }
}
