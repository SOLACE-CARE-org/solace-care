package care.solace.ai.brief;

import care.solace.ai.admin.Members;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The pre-session brief for the booking's specialist (SessionBrief.dc.html; D56). */
@RestController
class BriefController {

  private final Members members;
  private final Briefs briefs;

  BriefController(Members members, Briefs briefs) {
    this.members = members;
    this.briefs = briefs;
  }

  @GetMapping("/v1/specialist/bookings/{bookingId}/brief")
  Briefs.Brief brief(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId, Locale locale) {
    UUID specialist = members.specialist(token);
    return briefs
        .brief(bookingId, specialist, locale)
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }
}
