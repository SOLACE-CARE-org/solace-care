package care.solace.core.bookings;

import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** The Booking schema of contracts/openapi/core.yaml, for seekers, guests and specialists. */
@Component
class BookingViews {

  private final Bookings bookings;
  private final BookingStore store;
  private final SpecialistOffers offers;
  private final Accounts accounts;
  private final MessageSource messages;
  private final BookingRatings ratings;

  BookingViews(
      Bookings bookings,
      BookingStore store,
      SpecialistOffers offers,
      Accounts accounts,
      MessageSource messages,
      BookingRatings ratings) {
    this.bookings = bookings;
    this.store = store;
    this.offers = offers;
    this.accounts = accounts;
    this.messages = messages;
    this.ratings = ratings;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Specialist(UUID id, String fullName, String professionLabel) {}

  record Seeker(String label, boolean guest) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Booking(
      UUID id,
      String status,
      Specialist specialist,
      Seeker seeker,
      Instant start,
      Instant end,
      String modality,
      String sessionLanguage,
      long price,
      Instant payBy,
      int reschedulesLeft,
      Integer sessionNumber,
      String cancelledBy,
      Long refundAmount,
      Boolean rated,
      String guestPhone) {

    Booking withGuestPhone(String masked) {
      return new Booking(
          id,
          status,
          specialist,
          seeker,
          start,
          end,
          modality,
          sessionLanguage,
          price,
          payBy,
          reschedulesLeft,
          sessionNumber,
          cancelledBy,
          refundAmount,
          rated,
          masked);
    }
  }

  record Quote(long refundAmount, int refundPercent, int policyVersion) {}

  /**
   * @param forSpecialist the specialist sees who the seeker is (their chosen name, or "Guest"),
   *     never a contact detail
   */
  Booking of(BookingStore.Row b, boolean forSpecialist, Locale viewer) {
    var summary = offers.summary(b.specialistId(), viewer);
    Seeker seeker = null;
    if (forSpecialist) {
      boolean guest = b.seekerAccountId() == null;
      String label =
          guest
              ? null
              : accounts
                  .seekerDisplayName(b.seekerAccountId())
                  .filter(s -> !s.isBlank())
                  .orElse(null);
      seeker =
          new Seeker(
              label != null ? label : messages.getMessage("booking.guest-label", null, viewer),
              guest);
    }
    return new Booking(
        b.id(),
        b.status(),
        new Specialist(
            b.specialistId(),
            summary.map(SpecialistOffers.Summary::fullName).orElse(""),
            summary.map(SpecialistOffers.Summary::professionLabel).orElse(null)),
        seeker,
        b.start(),
        b.end(),
        b.modality(),
        b.sessionLanguage(),
        b.price(),
        b.status().equals("PENDING_PAYMENT") ? b.payBy() : null,
        b.status().equals("CONFIRMED") ? bookings.reschedulesLeft(b) : 0,
        store.sessionNumber(b),
        b.cancelledBy(),
        b.refundAmount(),
        b.status().equals("COMPLETED") ? !ratings.rated(List.of(b.id())).isEmpty() : null,
        null);
  }

  Quote of(Bookings.Quote q) {
    return new Quote(q.refundAmount(), q.refundPercent(), q.policyVersion());
  }
}
