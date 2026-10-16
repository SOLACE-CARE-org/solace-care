package care.solace.events;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a consumer's work at most once per event id (System Design, Async contracts). Kafka delivers
 * at least once, so every listener goes through this:
 *
 * <pre>{@code
 * @KafkaListener(topics = "core.booking")
 * void on(EventEnvelope event) {
 *   once.handle("tracking.booking-links", event, () -> links.record(event.payloadAs(...)));
 * }
 * }</pre>
 *
 * The event id is recorded in the same transaction as the work, so a failure rolls both back and
 * the redelivery runs the work again.
 */
public class IdempotentConsumer {

  private static final String CLAIM =
      "INSERT INTO processed_event (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public IdempotentConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs {@code work} unless {@code consumer} already handled this event.
   *
   * @param consumer stable name of the handler; two handlers of one event use different names
   * @return whether the work ran
   */
  public boolean handle(String consumer, EventEnvelope event, Runnable work) {
    return handle(consumer, event.eventId(), work);
  }

  public boolean handle(String consumer, UUID eventId, Runnable work) {
    Boolean ran =
        transactions.execute(
            status -> {
              if (jdbc.update(CLAIM, consumer, eventId) == 0) {
                return false;
              }
              work.run();
              return true;
            });
    return Boolean.TRUE.equals(ran);
  }
}
