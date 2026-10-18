package care.solace.core.reviews;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.guests.GuestAccess;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rate your session, the specialist's Reviews, published reviews on profiles and the moderation
 * queue (admin screen 13). Tags reviews and admin-reviews in contracts/openapi/core.yaml.
 */
@RestController
class RatingController {

  private final Ratings ratings;
  private final BookingRecords bookings;
  private final Accounts accounts;
  private final GuestAccess guests;
  private final SpecialistOffers specialists;
  private final StaffAccess access;
  private final AuditLog audit;
  private final Clock clock;

  RatingController(
      Ratings ratings,
      BookingRecords bookings,
      Accounts accounts,
      GuestAccess guests,
      SpecialistOffers specialists,
      StaffAccess access,
      AuditLog audit,
      Clock clock) {
    this.ratings = ratings;
    this.bookings = bookings;
    this.accounts = accounts;
    this.guests = guests;
    this.specialists = specialists;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
  }

  record RatingRequest(
      @Min(1) @Max(5) int stars,
      @Size(max = 4)
          List<@Pattern(regexp = "LISTENED|PRACTICAL_TOOLS|CALL_QUALITY|ON_TIME") String> tags,
      @Size(max = 1000) String text,
      @Size(max = 1000) String privateNote) {}

  record Reply(@NotBlank @Size(max = 600) String text) {}

  record Publication(boolean maskContacts) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PublicReview(
      UUID id, int stars, String text, Instant at, String sessionLanguage, String reply) {

    static PublicReview of(Ratings.Row r) {
      return new PublicReview(
          r.id(), r.stars(), r.publishedText(), r.sessionAt(), r.sessionLanguage(), r.reply());
    }
  }

  record Rating(double average, int count) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record MyReviews(Rating rating, List<PublicReview> items) {}

  record Specialist(UUID id, String fullName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ModerationItem(
      UUID id,
      int stars,
      String text,
      Specialist specialist,
      String seekerRef,
      Instant sessionAt,
      String sessionLanguage,
      List<String> flags,
      String status,
      String hiddenReason) {}

  // --- Seekers and guests -----------------------------------------------------------------------

  @PostMapping("/v1/bookings/{bookingId}/rating")
  @ResponseStatus(HttpStatus.CREATED)
  void rate(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody RatingRequest body) {
    var account = accounts.require(token);
    var booking =
        bookings
            .find(bookingId)
            .filter(b -> account.id().equals(b.seekerAccountId()))
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    rate(booking, body);
  }

  @PostMapping("/v1/guest/booking/rating")
  @ResponseStatus(HttpStatus.CREATED)
  void rateAsGuest(
      @RequestHeader("X-Guest-Token") String token, @Valid @RequestBody RatingRequest body) {
    var pass = guests.require(token, clock.instant());
    rate(bookings.find(pass.bookingId()).orElseThrow(), body);
  }

  private void rate(BookingRecords.Info booking, RatingRequest body) {
    ratings.rate(
        booking,
        body.stars(),
        body.tags() == null ? List.of() : body.tags().stream().distinct().toList(),
        body.text(),
        body.privateNote());
  }

  // --- Profiles and the specialist --------------------------------------------------------------

  @GetMapping("/v1/catalogue/specialists/{id}/reviews")
  List<PublicReview> published(
      @PathVariable UUID id, @RequestParam(defaultValue = "10") int limit) {
    if (limit < 1 || limit > 50) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    // Listed specialists only, as for their profile.
    if (specialists.offer(id, "AUDIO").isEmpty() && specialists.offer(id, "VIDEO").isEmpty()) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    return ratings.published(id, limit).stream().map(PublicReview::of).toList();
  }

  @GetMapping("/v1/me/reviews")
  MyReviews mine(@AuthenticationPrincipal Jwt token) {
    UUID id = specialist(token);
    var average = ratings.averages(List.of(id)).get(id);
    return new MyReviews(
        average == null ? null : new Rating(average.average(), average.count()),
        ratings.published(id, 50).stream().map(PublicReview::of).toList());
  }

  @PutMapping("/v1/me/reviews/{reviewId}/reply")
  PublicReview reply(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID reviewId,
      @Valid @RequestBody Reply body) {
    return PublicReview.of(ratings.reply(specialist(token), reviewId, body.text()));
  }

  private UUID specialist(Jwt token) {
    Account account = accounts.require(token);
    if (account.type() != Account.Type.SPECIALIST) {
      throw new SolaceException(ReviewError.NOT_A_SPECIALIST);
    }
    return account.id();
  }

  // --- Moderation -------------------------------------------------------------------------------

  @GetMapping("/admin/v1/reviews")
  List<ModerationItem> queue(@AuthenticationPrincipal Jwt token, Locale locale) {
    access.require(token, "reviews", Level.VIEW);
    return ratings.queue(200).stream().map(r -> item(r, locale)).toList();
  }

  @PostMapping("/admin/v1/reviews/{reviewId}/publication")
  ModerationItem publish(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID reviewId,
      @RequestBody(required = false) Publication body,
      Locale locale) {
    var staff = access.require(token, "reviews", Level.CHANGE);
    boolean mask = body != null && body.maskContacts();
    var row = ratings.publish(reviewId, mask, staff.id(), staff.name());
    audit.adminAction(
        "reviews.publish",
        "review",
        reviewId.toString(),
        mask ? Map.of("text", "masked") : Map.of());
    return item(row, locale);
  }

  @PostMapping("/admin/v1/reviews/{reviewId}/hiding")
  ModerationItem hide(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID reviewId,
      @Valid @RequestBody Reason body,
      Locale locale) {
    var staff = access.require(token, "reviews", Level.CHANGE);
    var row = ratings.hide(reviewId, body.reason(), staff.id(), staff.name());
    audit.adminAction("reviews.hide", "review", reviewId.toString(), Map.of("reason", "set"));
    return item(row, locale);
  }

  private ModerationItem item(Ratings.Row r, Locale locale) {
    return new ModerationItem(
        r.id(),
        r.stars(),
        r.status().equals("PUBLISHED") ? r.publishedText() : r.text(),
        new Specialist(
            r.specialistId(),
            specialists
                .summary(r.specialistId(), locale)
                .map(SpecialistOffers.Summary::fullName)
                .orElse("")),
        r.seekerRef(),
        r.sessionAt(),
        r.sessionLanguage(),
        r.flags(),
        r.status(),
        r.hiddenReason());
  }
}
