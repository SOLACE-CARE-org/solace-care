package care.solace.core.notifications;

import care.solace.core.admin.StaffAccountCreated;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emails to staff (notification-copy.md, Emails; D47), in English and French together: a new member
 * has not chosen a portal language yet.
 */
@Component
class StaffEmails {

  private static final Logger LOG = LoggerFactory.getLogger(StaffEmails.class);

  private final Delivery delivery;

  StaffEmails(Delivery delivery) {
    this.delivery = delivery;
  }

  @EventListener
  void on(StaffAccountCreated created) {
    bilingual(created.email(), "email.staff-account");
  }

  /** Subject "EN / FR"; the English body, then the French one. */
  void bilingual(String address, String key, Object... arguments) {
    try {
      delivery.email(
          address,
          delivery.text(key + ".subject", Locale.ENGLISH, arguments)
              + " / "
              + delivery.text(key + ".subject", Locale.FRENCH, arguments),
          delivery.text(key + ".body", Locale.ENGLISH, arguments)
              + "\n\n"
              + delivery.text(key + ".body", Locale.FRENCH, arguments));
    } catch (RuntimeException e) {
      LOG.warn("A staff email ({}) could not be sent", key);
    }
  }
}
