package care.solace.core.payments;

import care.solace.core.admin.StaffDirectory;
import care.solace.core.notifications.StaffPush;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells Finance when a refund or a payout run waits for a second person (notification-copy.md,
 * Staff; D47), once the change is committed.
 */
@Component
class FinanceAlerts {

  private static final Logger LOG = LoggerFactory.getLogger(FinanceAlerts.class);

  /** A refund was proposed. */
  record RefundWaiting() {}

  /** A payout run was submitted by this person, who cannot approve it. */
  record RunWaiting(String submittedBy) {}

  private final StaffDirectory staff;
  private final StaffPush push;
  private final JdbcClient db;

  FinanceAlerts(StaffDirectory staff, StaffPush push, JdbcClient db) {
    this.staff = staff;
    this.push = push;
    this.db = db;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(RefundWaiting waiting) {
    try {
      int n =
          db.sql("SELECT count(*) FROM refund WHERE status = 'PROPOSED'")
              .query(Integer.class)
              .single();
      push.send(ids(staff.activeWithRole("FINANCE")), "push.staff.refunds", false, n);
    } catch (RuntimeException e) {
      LOG.warn("Telling Finance about a refund failed", e);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(RunWaiting waiting) {
    try {
      var approvers =
          Stream.concat(
                  staff.activeWithRole("FINANCE").stream(),
                  staff.activeWithRole("SUPER_ADMIN").stream())
              .map(StaffDirectory.Recipient::id)
              .filter(id -> !id.equals(waiting.submittedBy()))
              .distinct()
              .toList();
      push.send(approvers, "push.staff.payout-run", false);
    } catch (RuntimeException e) {
      LOG.warn("Telling Finance about a payout run failed", e);
    }
  }

  private static List<String> ids(List<StaffDirectory.Recipient> people) {
    return people.stream().map(StaffDirectory.Recipient::id).toList();
  }
}
