package care.solace.core.bookings;

import care.solace.core.guests.GuestContactClaimed;
import care.solace.core.scheduling.Availability;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import care.solace.events.Topics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking lifecycle (D32) and its policy (D33). A booking is made from a 10-minute hold, which
 * becomes a calendar entry at once; it waits for payment until the hold window ends, then expires.
 * Cancelling frees the time and refunds what the policy in force when it was made gives.
 */
@Service
class Bookings implements BookingRecords {

  static final String TOPIC = "core.booking";
  private static final Logger LOG = LoggerFactory.getLogger(Bookings.class);
  private static final List<String> ACTIVE = List.of("PENDING_PAYMENT", "CONFIRMED");

  private final BookingStore store;
  private final Availability availability;
  private final SpecialistOffers offers;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Bookings(
      BookingStore store,
      Availability availability,
      SpecialistOffers offers,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.store = store;
    this.availability = availability;
    this.offers = offers;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  /** Who is booking: a member or a guest contact, and the app language for messages. */
  record Booker(UUID seekerAccountId, UUID guestContactId, Locale locale) {}

  enum Actor {
    SEEKER,
    SPECIALIST,
    STAFF,
    SYSTEM
  }

  record Quote(long refundAmount, int refundPercent, int policyVersion) {}

  // --- Making a booking ------------------------------------------------------------------------

  BookingStore.Row create(Booker booker, UUID holdId, String sessionLanguage, String source) {
    var hold =
        availability
            .findHold(holdId)
            .orElseThrow(() -> new SolaceException(BookingError.HOLD_EXPIRED));
    var offer =
        offers
            .offer(hold.specialistId(), hold.modality())
            .orElseThrow(() -> new SolaceException(BookingError.NOT_BOOKABLE));
    if (!offers.bookableIn(hold.specialistId(), sessionLanguage)) {
      throw new SolaceException(BookingError.NOT_BOOKABLE);
    }
    UUID id = UUID.randomUUID();
    var reservation = availability.reserve(id, holdId, hold.specialistId());
    var row =
        new BookingStore.Row(
            id,
            booker.seekerAccountId(),
            booker.guestContactId(),
            hold.specialistId(),
            hold.modality(),
            offer.durationMinutes(),
            offer.price(),
            sessionLanguage,
            booker.locale(),
            reservation.start(),
            reservation.end(),
            "PENDING_PAYMENT",
            source,
            store.currentPolicy().version(),
            0,
            reservation.holdExpiresAt(),
            null,
            null);
    try {
      transactions.executeWithoutResult(s -> store.insert(row));
    } catch (RuntimeException e) {
      availability.releaseBooking(id);
      throw e;
    }
    return row;
  }

  // --- Cancelling and moving -------------------------------------------------------------------

  Quote quote(BookingStore.Row b, Actor by, Instant now) {
    if (!ACTIVE.contains(b.status()) || !b.start().isAfter(now)) {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    var policy = store.policy(b.policyVersion());
    if (b.status().equals("PENDING_PAYMENT")) {
      return new Quote(0, 0, policy.version()); // nothing was paid
    }
    int percent;
    if (by == Actor.SPECIALIST || by == Actor.STAFF) {
      percent = 100;
    } else {
      long hours = Duration.between(now, b.start()).toHours();
      percent =
          hours >= policy.fullRefundHours()
              ? 100
              : hours >= policy.partialRefundHours() ? policy.partialRefundPercent() : 0;
    }
    return new Quote(b.price() * percent / 100, percent, policy.version());
  }

  BookingStore.Row cancel(BookingStore.Row b, Actor by, String reason) {
    Instant now = clock.instant();
    Quote quote = quote(b, by, now);
    Boolean done =
        transactions.execute(
            s -> {
              if (!store.cancel(b.id(), ACTIVE, by.name(), reason, quote.refundAmount(), now)) {
                return false;
              }
              events.publishEvent(
                  new BookingCancelled(
                      b.id(),
                      b.specialistId(),
                      quote.refundAmount(),
                      by.name(),
                      b.policyVersion()));
              events.publishEvent(
                  Events.envelope(
                      "core.booking.cancelled",
                      1,
                      subjectOf(b).toString(),
                      b.seekerLocale().getLanguage(),
                      new CancelledPayload(
                          b.id(),
                          b.specialistId(),
                          b.source(),
                          b.guestContactId() != null && b.seekerAccountId() == null,
                          by.name())));
              return true;
            });
    if (!Boolean.TRUE.equals(done)) {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    availability.releaseBooking(b.id());
    return store.find(b.id()).orElseThrow();
  }

  BookingStore.Row reschedule(BookingStore.Row b, UUID holdId) {
    Instant now = clock.instant();
    if (!b.status().equals("CONFIRMED") || !b.start().isAfter(now)) {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    var policy = store.policy(b.policyVersion());
    if (b.reschedules() >= policy.maxReschedules()) {
      throw new SolaceException(BookingError.RESCHEDULE_LIMIT);
    }
    if (Duration.between(now, b.start()).toHours() < policy.rescheduleMinHours()) {
      throw new SolaceException(BookingError.RESCHEDULE_TOO_LATE);
    }
    var hold =
        availability
            .findHold(holdId)
            .orElseThrow(() -> new SolaceException(BookingError.HOLD_EXPIRED));
    if (!hold.specialistId().equals(b.specialistId()) || !hold.modality().equals(b.modality())) {
      throw new SolaceException(BookingError.RESCHEDULE_MISMATCH);
    }
    var moved = availability.move(b.id(), holdId, b.specialistId());
    transactions.executeWithoutResult(
        s -> {
          store.reschedule(b.id(), moved.start(), moved.end(), now);
          events.publishEvent(new BookingRescheduled(b.id(), moved.start()));
        });
    return store.find(b.id()).orElseThrow();
  }

  int reschedulesLeft(BookingStore.Row b) {
    return Math.max(0, store.policy(b.policyVersion()).maxReschedules() - b.reschedules());
  }

  // --- The lifecycle for other modules ---------------------------------------------------------

  @Override
  public Optional<Info> find(UUID bookingId) {
    return store.find(bookingId).map(BookingStore.Row::info);
  }

  @Override
  public boolean confirm(UUID bookingId, Instant now) {
    return Boolean.TRUE.equals(
        transactions.execute(
            s -> {
              if (!store.move(
                  bookingId, List.of("PENDING_PAYMENT"), "CONFIRMED", "confirmed_at", now)) {
                return false;
              }
              store.find(bookingId).ifPresent(b -> publish("core.booking.confirmed", b));
              return true;
            }));
  }

  @Override
  public boolean start(UUID bookingId, Instant now) {
    return store.move(bookingId, List.of("CONFIRMED"), "IN_PROGRESS", null, now);
  }

  @Override
  public boolean complete(UUID bookingId, Instant now) {
    return Boolean.TRUE.equals(
        transactions.execute(
            s -> {
              if (!store.move(
                  bookingId, List.of("CONFIRMED", "IN_PROGRESS"), "COMPLETED", null, now)) {
                return false;
              }
              store
                  .find(bookingId)
                  .ifPresent(
                      b -> {
                        events.publishEvent(new BookingCompleted(b.id(), b.specialistId(), now));
                        publish("core.booking.completed", b);
                      });
              return true;
            }));
  }

  @Override
  public boolean noShow(UUID bookingId, NoShowBy who, Instant now) {
    return Boolean.TRUE.equals(
        transactions.execute(
            s -> {
              if (!store.move(
                  bookingId, List.of("CONFIRMED", "IN_PROGRESS"), "NO_SHOW", null, now)) {
                return false;
              }
              var b = store.find(bookingId).orElseThrow();
              events.publishEvent(
                  new BookingNoShow(
                      bookingId, who, noShowRefundPercent(bookingId, who), b.policyVersion(), now));
              return true;
            }));
  }

  @Override
  public List<Info> confirmedStartedBefore(Instant before) {
    return store.inStatusBefore("CONFIRMED", "starts_at", before, 100).stream()
        .map(BookingStore.Row::info)
        .toList();
  }

  @Override
  public List<Info> confirmedStartingBetween(Instant from, Instant to) {
    return store.confirmedStartingBetween(from, to).stream().map(BookingStore.Row::info).toList();
  }

  @Override
  public List<Info> inProgressEndedBefore(Instant before) {
    return store.inStatusBefore("IN_PROGRESS", "ends_at", before, 100).stream()
        .map(BookingStore.Row::info)
        .toList();
  }

  @Override
  public int noShowRefundPercent(UUID bookingId, NoShowBy who) {
    var b = store.find(bookingId).orElseThrow();
    var policy = store.policy(b.policyVersion());
    return who == NoShowBy.SPECIALIST
        ? policy.specialistNoShowRefundPercent()
        : policy.seekerNoShowRefundPercent();
  }

  // --- Housekeeping ----------------------------------------------------------------------------

  /** Unpaid when the hold window ends: the booking expires and its time is free again (D32). */
  @Scheduled(
      fixedDelayString = "${solace.bookings.expiry-interval:PT30S}",
      initialDelayString = "PT30S")
  void expireUnpaid() {
    Instant now = clock.instant();
    for (UUID id : store.unpaidBefore(now, 100)) {
      try {
        if (store.move(id, List.of("PENDING_PAYMENT"), "EXPIRED", null, now)) {
          availability.releaseBooking(id);
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not expire booking {}", id, e);
      }
    }
  }

  /** A guest signed up with the same phone: their bookings move into the account (D34). */
  @ApplicationModuleListener
  void claim(GuestContactClaimed claimed) {
    store.claim(claimed.guestContactId(), claimed.accountId());
  }

  @Bean
  static KafkaAdmin.NewTopics bookingTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }

  private void publish(String type, BookingStore.Row b) {
    events.publishEvent(
        Events.envelope(
            type,
            1,
            subjectOf(b).toString(),
            b.seekerLocale().getLanguage(),
            new Payload(b.id(), b.specialistId(), b.source(), b.seekerAccountId() == null)));
  }

  private static UUID subjectOf(BookingStore.Row b) {
    return b.seekerAccountId() != null ? b.seekerAccountId() : b.guestContactId();
  }

  /** core.booking.confirmed and completed v1: ids and codes only. */
  record Payload(UUID bookingId, UUID specialistId, String source, boolean guest) {}

  /** core.booking.cancelled v1. */
  record CancelledPayload(
      UUID bookingId, UUID specialistId, String source, boolean guest, String cancelledBy) {}
}
