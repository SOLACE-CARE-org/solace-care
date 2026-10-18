package care.solace.core.bookings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A booking's money as the booking detail shows it, and refunds staff propose when resolving a
 * dispute (D41). Payments implements it, so bookings never reads that module's tables.
 */
public interface BookingMoney {

  /** The latest payment for the booking, if any was started. */
  Optional<Money> money(UUID bookingId);

  /**
   * A refund proposed by a staff member; a different Finance member approves it (D14).
   *
   * @return the refund's id
   */
  UUID proposeRefund(UUID bookingId, long amount, String reason, String staffId, String staffName);

  record Money(
      String method,
      String maskedPhone,
      long amount,
      String status,
      long held,
      long refunded,
      List<Refund> refunds) {}

  record Refund(UUID id, long amount, String status, Instant createdAt) {}
}
