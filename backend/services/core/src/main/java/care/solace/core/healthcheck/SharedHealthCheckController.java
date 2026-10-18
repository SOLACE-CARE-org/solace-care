package care.solace.core.healthcheck;

import care.solace.audit.AuditLog;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The booked specialist reads the answers a seeker chose to share for that booking (System Design,
 * Health check, Guests and storage; D55). Every read and refusal is audited.
 */
@RestController
class SharedHealthCheckController {

  private final Accounts accounts;
  private final BookingRecords bookings;
  private final Responses responses;
  private final Versions versions;
  private final AuditLog audit;

  SharedHealthCheckController(
      Accounts accounts,
      BookingRecords bookings,
      Responses responses,
      Versions versions,
      AuditLog audit) {
    this.accounts = accounts;
    this.bookings = bookings;
    this.responses = responses;
    this.versions = versions;
    this.audit = audit;
  }

  record Answer(String question, List<String> answers) {}

  record Shared(
      String version, Instant completedAt, List<Answer> answers, List<Engine.Score> scores) {}

  @GetMapping("/v1/specialist/bookings/{bookingId}/health-check")
  Shared read(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId, Locale locale) {
    Account me = accounts.require(token);
    var booking =
        bookings.find(bookingId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (me.type() != Account.Type.SPECIALIST || !booking.specialistId().equals(me.id())) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    var shared = responses.sharedWith(bookingId);
    String subject =
        booking.seekerAccountId() == null ? null : booking.seekerAccountId().toString();
    if (shared.isEmpty()) {
      audit.denied("health-check.shared.read", "booking", bookingId.toString(), subject);
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    var r = shared.get();
    audit.read("health-check.shared.read", "health-check-response", r.id().toString(), subject);
    var v = versions.require(r.versionId());
    boolean fr = "fr".equals(locale.getLanguage());
    List<Answer> answers = new ArrayList<>();
    for (Question q : Engine.visible(v.questions(), r.state().answers())) {
      List<String> chosen = r.state().answers().getOrDefault(q.code(), List.of());
      if (chosen.isEmpty()) {
        continue;
      }
      answers.add(
          new Answer(
              fr ? q.textFr() : q.textEn(),
              q.options().stream()
                  .filter(o -> chosen.contains(o.code()))
                  .map(o -> fr ? o.labelFr() : o.labelEn())
                  .toList()));
    }
    return new Shared(
        v.number(),
        r.completedAt(),
        answers,
        r.state().scores() == null ? List.of() : r.state().scores());
  }
}
