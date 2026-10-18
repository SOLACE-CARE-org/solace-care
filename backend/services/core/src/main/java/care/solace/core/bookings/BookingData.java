package care.solace.core.bookings;

import care.solace.core.identity.PersonalData;
import care.solace.core.scheduling.Availability;
import care.solace.core.specialists.SpecialistOffers;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A member's bookings in their copy of their data, and what erasure does to them (D40): bookings
 * stay as the accounting record, linked to the tombstone; unpaid ones end and free their time.
 */
@Component
class BookingData implements PersonalData {

  private final BookingStore store;
  private final DisputeStore disputes;
  private final Availability availability;
  private final SpecialistOffers specialists;
  private final Clock clock;

  BookingData(
      BookingStore store,
      DisputeStore disputes,
      Availability availability,
      SpecialistOffers specialists,
      Clock clock) {
    this.store = store;
    this.disputes = disputes;
    this.availability = availability;
    this.specialists = specialists;
    this.clock = clock;
  }

  @Override
  public String section() {
    return "bookings";
  }

  @Override
  public Object export(UUID accountId) {
    return store.forAccount(accountId).stream()
        .map(
            b -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", b.id());
              m.put(
                  "specialist",
                  specialists
                      .summary(b.specialistId(), Locale.ENGLISH)
                      .map(SpecialistOffers.Summary::fullName)
                      .orElse(null));
              m.put("start", b.start());
              m.put("end", b.end());
              m.put("modality", b.modality());
              m.put("sessionLanguage", b.sessionLanguage());
              m.put("price", b.price());
              m.put("status", b.status());
              m.put("cancelledBy", b.cancelledBy());
              m.put("refundAmount", b.refundAmount());
              return m;
            })
        .toList();
  }

  /** A paid session still to come, or under way, must be cancelled or held first. */
  @Override
  public Optional<String> blocksErasure(UUID accountId) {
    return store.forAccount(accountId).stream()
            .anyMatch(b -> List.of("CONFIRMED", "IN_PROGRESS").contains(b.status()))
        ? Optional.of("privacy.upcoming-sessions")
        : Optional.empty();
  }

  @Override
  public void erase(UUID accountId) {
    Instant now = clock.instant();
    var bookings = store.forAccount(accountId);
    // A dispute note may repeat what the person said (D41).
    disputes.forgetNotes(bookings.stream().map(BookingStore.Row::id).toList());
    for (var b : bookings) {
      if (b.status().equals("PENDING_PAYMENT")
          && store.move(b.id(), List.of("PENDING_PAYMENT"), "EXPIRED", null, now)) {
        availability.releaseBooking(b.id());
      }
    }
  }
}
