package care.solace.core.privacy;

import care.solace.core.admin.StaffDirectory;
import care.solace.core.notifications.Delivery;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Data requests due": a daily email to Operations while requests fall due within the week
 * (notification-copy.md, Staff; D47). In English and French together, like every staff email.
 */
@Component
class DataRequestReminders {

  private static final Logger LOG = LoggerFactory.getLogger(DataRequestReminders.class);

  private final JdbcClient db;
  private final StaffDirectory staff;
  private final Delivery delivery;
  private final Clock clock;

  DataRequestReminders(JdbcClient db, StaffDirectory staff, Delivery delivery, Clock clock) {
    this.db = db;
    this.staff = staff;
    this.delivery = delivery;
    this.clock = clock;
  }

  /** Every morning at 08:00 Douala time; not in the specs. */
  @Scheduled(cron = "0 0 8 * * *", zone = "Africa/Douala")
  void remind() {
    int due = dueThisWeek();
    if (due == 0) {
      return;
    }
    for (var person : staff.activeWithRole("OPERATIONS")) {
      if (person.email() == null || person.email().isBlank()) {
        continue;
      }
      try {
        delivery.email(
            person.email(),
            delivery.text("email.data-requests-due.subject", Locale.ENGLISH, due)
                + " / "
                + delivery.text("email.data-requests-due.subject", Locale.FRENCH, due),
            delivery.text("email.data-requests-due.body", Locale.ENGLISH, due)
                + "\n\n"
                + delivery.text("email.data-requests-due.body", Locale.FRENCH, due));
      } catch (RuntimeException e) {
        LOG.warn("The data-requests email to Operations could not be sent");
      }
    }
  }

  int dueThisWeek() {
    return db.sql(
            """
            SELECT count(*) FROM data_request
             WHERE status IN ('PREPARING', 'REQUESTED') AND due_at < :week
            """)
        .param("week", Timestamp.from(clock.instant().plus(Duration.ofDays(7))))
        .query(Integer.class)
        .single();
  }
}
