package care.solace.core.payments;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.identity.PersonalData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A member's payments and refunds in their copy of their data; erasure removes the payer numbers
 * and keeps the amounts, which accounting law needs (D40).
 */
@Component
class PaymentData implements PersonalData {

  private final BookingRecords bookings;
  private final JdbcClient db;

  PaymentData(BookingRecords bookings, JdbcClient db) {
    this.bookings = bookings;
    this.db = db;
  }

  @Override
  public String section() {
    return "payments";
  }

  @Override
  public Object export(UUID accountId) {
    List<UUID> ids = bookings.idsFor(accountId);
    if (ids.isEmpty()) {
      return Map.of("payments", List.of(), "refunds", List.of());
    }
    List<Map<String, Object>> payments =
        db.sql(
                """
                SELECT id, booking_id, method, phone, amount, status, created_at FROM payment
                 WHERE booking_id IN (:ids) ORDER BY created_at
                """)
            .param("ids", ids)
            .query(
                (rs, n) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", rs.getObject("id", UUID.class));
                  m.put("bookingId", rs.getObject("booking_id", UUID.class));
                  m.put("method", rs.getString("method"));
                  m.put("phone", rs.getString("phone"));
                  m.put("amount", rs.getLong("amount"));
                  m.put("status", rs.getString("status"));
                  m.put("createdAt", rs.getTimestamp("created_at").toInstant());
                  return m;
                })
            .list();
    List<Map<String, Object>> refunds =
        db.sql(
                """
                SELECT id, booking_id, amount, status, created_at FROM refund
                 WHERE booking_id IN (:ids) ORDER BY created_at
                """)
            .param("ids", ids)
            .query(
                (rs, n) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", rs.getObject("id", UUID.class));
                  m.put("bookingId", rs.getObject("booking_id", UUID.class));
                  m.put("amount", rs.getLong("amount"));
                  m.put("status", rs.getString("status"));
                  m.put("createdAt", rs.getTimestamp("created_at").toInstant());
                  return m;
                })
            .list();
    return Map.of("payments", payments, "refunds", refunds);
  }

  /** A refund still being decided or sent needs the wallet number. */
  @Override
  public Optional<String> blocksErasure(UUID accountId) {
    List<UUID> ids = bookings.idsFor(accountId);
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    boolean open =
        db.sql(
                """
                SELECT EXISTS (SELECT 1 FROM refund WHERE booking_id IN (:ids)
                                AND status IN ('PROPOSED', 'APPROVED', 'SENT', 'FAILED'))
                """)
            .param("ids", ids)
            .query(Boolean.class)
            .single();
    return open ? Optional.of("privacy.open-refund") : Optional.empty();
  }

  @Override
  public void erase(UUID accountId) {
    List<UUID> ids = bookings.idsFor(accountId);
    if (!ids.isEmpty()) {
      db.sql("UPDATE payment SET phone = NULL WHERE booking_id IN (:ids)")
          .param("ids", ids)
          .update();
    }
  }
}
