package care.solace.tracking.rules;

import care.solace.events.EventEnvelope;
import care.solace.events.Events;
import care.solace.events.IdempotentConsumer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Links a recommendation to the booking it led to (Tracking Service Spec, Events): on
 * core.booking.confirmed with a recommendationId, the recommendation becomes BOOKED and
 * tracking.recommendation.resolved goes out.
 */
@Component
public class BookingOutcomes {

  static final String CONSUMER = "tracking.booking-confirmed";

  /** The part of core.booking.confirmed v1 Tracking reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BookingConfirmed(UUID bookingId, UUID recommendationId) {}

  private final JdbcClient db;
  private final IdempotentConsumer once;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  BookingOutcomes(
      JdbcClient db,
      IdempotentConsumer once,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      Clock clock) {
    this.db = db;
    this.once = once;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  @KafkaListener(topics = "core.booking")
  public void on(EventEnvelope event) {
    if (!"core.booking.confirmed".equals(event.type()) || event.subjectId() == null) {
      return;
    }
    var booking = event.payloadAs(BookingConfirmed.class);
    if (booking.recommendationId() == null) {
      return;
    }
    once.handle(
        CONSUMER,
        event,
        () ->
            transactions.executeWithoutResult(
                s -> {
                  UUID user = UUID.fromString(event.subjectId());
                  var code =
                      db.sql(
                              """
                              UPDATE recommendation
                                 SET status = 'BOOKED', booking_id = :b, resolved_at = :now
                               WHERE id = :id AND user_id = :u
                                 AND status IN ('NEW', 'SEEN', 'SNOOZED')
                              RETURNING rule_code
                              """)
                          .param("b", booking.bookingId())
                          .param("now", Timestamp.from(clock.instant()))
                          .param("id", booking.recommendationId())
                          .param("u", user)
                          .query(String.class)
                          .optional();
                  code.ifPresent(
                      c ->
                          events.publishEvent(
                              Events.envelope(
                                  "tracking.recommendation.resolved",
                                  1,
                                  user.toString(),
                                  event.locale(),
                                  new PatternCheck.RecommendationResolved(
                                      booking.recommendationId(), c, "BOOKED"))));
                }));
  }
}
