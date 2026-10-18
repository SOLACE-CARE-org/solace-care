package care.solace.core.messaging;

import care.solace.core.guests.GuestAccess;
import care.solace.core.identity.Accounts;
import care.solace.core.notifications.Delivery;
import care.solace.core.scheduling.Availability;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends one message to one person (notification-copy.md; D38): push to members, in their app
 * language and time zone, honouring discreet mode and, for what can wait, quiet hours; SMS to
 * guests with a booking link; email for records. Each message about a booking goes once.
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(MessagingSettings.class)
class Notifier {

  private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

  /** Fills a message's placeholders in the recipient's language and time zone. */
  @FunctionalInterface
  interface Args {
    Object[] of(Locale locale, ZoneId zone);
  }

  private final JdbcClient db;
  private final Delivery delivery;
  private final Accounts accounts;
  private final GuestAccess guests;
  private final Availability availability;
  private final MessagingSettings settings;
  private final org.springframework.transaction.support.TransactionTemplate ownTransaction;
  private final Clock clock;

  Notifier(
      JdbcClient db,
      Delivery delivery,
      Accounts accounts,
      GuestAccess guests,
      Availability availability,
      MessagingSettings settings,
      org.springframework.transaction.PlatformTransactionManager transactions,
      Clock clock) {
    this.ownTransaction =
        new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.ownTransaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.db = db;
    this.delivery = delivery;
    this.accounts = accounts;
    this.guests = guests;
    this.availability = availability;
    this.settings = settings;
    this.clock = clock;
  }

  // --- Push -------------------------------------------------------------------------------------

  /**
   * @param canWait true for what quiet hours may hold back: reminders, rating asks, new reviews
   */
  void push(UUID accountId, String kind, UUID bookingId, boolean canWait, Args args) {
    Locale locale = Formats.language(accounts.appLanguage(accountId).orElse(Locale.ENGLISH));
    ZoneId zone = zoneOf(accountId);
    String text =
        delivery.text(
            "push." + kind.toLowerCase(Locale.ROOT).replace('_', '-'),
            locale,
            args.of(locale, zone));
    Instant now = clock.instant();
    Instant sendAfter = canWait ? afterQuietHours(accountId, now, zone) : now;
    UUID id = UUID.randomUUID();
    int inserted =
        db.sql(
                """
                INSERT INTO notification (id, account_id, kind, channel, booking_id, text, status,
                                          send_after, dedupe_key, created_at)
                VALUES (:id, :a, :k, 'PUSH', :b, :t, 'WAITING', :after, :key, :now)
                ON CONFLICT (dedupe_key) DO NOTHING
                """)
            .param("id", id)
            .param("a", accountId)
            .param("k", kind)
            .param("b", bookingId)
            .param("t", text)
            .param("after", Timestamp.from(sendAfter))
            .param("key", dedupe(kind, bookingId, accountId.toString(), "PUSH"))
            .param("now", Timestamp.from(now))
            .update();
    if (inserted == 1 && !sendAfter.isAfter(now)) {
      deliver(id, accountId, text, bookingId);
    }
  }

  /** Pushes held back by quiet hours, once they end. */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  void deliverWaiting() {
    try {
      db.sql(
              """
              SELECT id, account_id, text, booking_id FROM notification
               WHERE status = 'WAITING' AND channel = 'PUSH' AND send_after <= :now
               ORDER BY send_after LIMIT 200
              """)
          .param("now", Timestamp.from(clock.instant()))
          .query(
              (rs, n) ->
                  new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    rs.getString("text"),
                    rs.getObject("booking_id", UUID.class)
                  })
          .list()
          .forEach(r -> deliver((UUID) r[0], (UUID) r[1], (String) r[2], (UUID) r[3]));
    } catch (RuntimeException e) {
      LOG.warn("Delivering waiting pushes failed", e);
    }
  }

  private void deliver(UUID id, UUID accountId, String text, UUID bookingId) {
    List<String> tokens =
        db.sql("SELECT token FROM push_device WHERE account_id = :a")
            .param("a", accountId)
            .query(String.class)
            .list();
    boolean discreet = setting(accountId, "discreet", false);
    Locale locale = Formats.language(accounts.appLanguage(accountId).orElse(Locale.ENGLISH));
    Map<String, String> data = new HashMap<>();
    if (bookingId != null) {
      data.put("bookingId", bookingId.toString());
    }
    var gone =
        delivery.push(tokens, discreet ? delivery.text("push.discreet", locale) : text, data);
    for (String token : gone) {
      db.sql("DELETE FROM push_device WHERE token = :t").param("t", token).update();
    }
    db.sql("UPDATE notification SET status = 'SENT', sent_at = :now WHERE id = :id")
        .param("id", id)
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  // --- SMS to guests ----------------------------------------------------------------------------

  /**
   * An SMS to a guest. When the copy has a {link}, {@code withLink} adds a fresh booking-scoped
   * link valid until {@code linkUntil} as the last placeholder.
   */
  void sms(
      UUID guestContactId,
      String kind,
      UUID bookingId,
      Instant linkUntil,
      boolean withLink,
      Args args) {
    Optional<String> phone = guests.phone(guestContactId);
    if (phone.isEmpty()) {
      return;
    }
    Locale locale = Formats.language(guests.locale(guestContactId).orElse(Locale.FRENCH));
    ZoneId zone = ZoneId.of(settings.defaultTimeZone());
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    int inserted =
        db.sql(
                """
                INSERT INTO notification (id, guest_contact_id, kind, channel, booking_id, status,
                                          send_after, dedupe_key, created_at)
                VALUES (:id, :g, :k, 'SMS', :b, 'SENT', :now, :key, :now)
                ON CONFLICT (dedupe_key) DO NOTHING
                """)
            .param("id", id)
            .param("g", guestContactId)
            .param("k", kind)
            .param("b", bookingId)
            .param("key", dedupe(kind, bookingId, guestContactId.toString(), "SMS"))
            .param("now", Timestamp.from(now))
            .update();
    if (inserted != 1) {
      return;
    }
    Object[] values = args.of(locale, zone);
    if (withLink) {
      Object[] more = java.util.Arrays.copyOf(values, values.length + 1);
      more[values.length] =
          settings.linkBase()
              // Committed before the SMS leaves, so the link works even if this listener's
              // transaction later rolls back.
              + ownTransaction.execute(
                  s -> guests.issueLinkToken(guestContactId, bookingId, linkUntil));
      values = more;
    }
    if (locale == Locale.FRENCH) {
      for (int i = 0; i < values.length; i++) {
        if (values[i] instanceof String s) {
          values[i] = Formats.gsmSafe(s);
        }
      }
    }
    try {
      delivery.sms(
          phone.get(),
          delivery.text("sms." + kind.toLowerCase(Locale.ROOT).replace('_', '-'), locale, values));
    } catch (RuntimeException e) {
      LOG.warn("SMS {} for booking {} could not be sent", kind, bookingId, e);
      db.sql("UPDATE notification SET status = 'FAILED' WHERE id = :id").param("id", id).update();
    }
  }

  // --- Email ------------------------------------------------------------------------------------

  /** An email record to a member who gave an address; nothing otherwise. */
  void email(UUID accountId, String kind, String dedupeOn, Args args) {
    Optional<String> address = accounts.email(accountId).filter(a -> !a.isBlank());
    if (address.isEmpty()) {
      return;
    }
    Locale locale = Formats.language(accounts.appLanguage(accountId).orElse(Locale.ENGLISH));
    ZoneId zone = zoneOf(accountId);
    Instant now = clock.instant();
    int inserted =
        db.sql(
                """
                INSERT INTO notification (id, account_id, kind, channel, status, send_after,
                                          dedupe_key, created_at)
                VALUES (:id, :a, :k, 'EMAIL', 'SENT', :now, :key, :now)
                ON CONFLICT (dedupe_key) DO NOTHING
                """)
            .param("id", UUID.randomUUID())
            .param("a", accountId)
            .param("k", kind)
            .param("key", "EMAIL:" + kind + ":" + accountId + ":" + dedupeOn)
            .param("now", Timestamp.from(now))
            .update();
    if (inserted != 1) {
      return;
    }
    Object[] values = args.of(locale, zone);
    String key = "email." + kind.toLowerCase(Locale.ROOT).replace('_', '-');
    delivery.email(
        address.get(),
        delivery.text(key + ".subject", locale, values),
        delivery.text(key + ".body", locale, values));
  }

  // --- Who, where, when -------------------------------------------------------------------------

  /** A member's latest device's zone, a specialist's working zone, else the default (D38). */
  ZoneId zoneOf(UUID accountId) {
    return db.sql(
            "SELECT time_zone FROM push_device WHERE account_id = :a ORDER BY updated_at DESC LIMIT 1")
        .param("a", accountId)
        .query(String.class)
        .optional()
        .or(() -> availability.timeZone(accountId))
        .flatMap(Notifier::zone)
        .orElse(ZoneId.of(settings.defaultTimeZone()));
  }

  Locale languageOf(UUID accountId) {
    return Formats.language(accounts.appLanguage(accountId).orElse(Locale.ENGLISH));
  }

  boolean sessionReminders(UUID accountId) {
    return setting(accountId, "session_reminders", true);
  }

  private Instant afterQuietHours(UUID accountId, Instant now, ZoneId zone) {
    var quiet =
        db.sql("SELECT quiet_start, quiet_end FROM notification_setting WHERE account_id = :a")
            .param("a", accountId)
            .query(
                (rs, n) ->
                    new LocalTime[] {
                      rs.getObject("quiet_start", LocalTime.class),
                      rs.getObject("quiet_end", LocalTime.class)
                    })
            .optional();
    LocalTime start =
        quiet.map(q -> q[0]).filter(java.util.Objects::nonNull).orElse(settings.quietStart());
    LocalTime end =
        quiet.map(q -> q[1]).filter(java.util.Objects::nonNull).orElse(settings.quietEnd());
    return QuietHours.next(now, zone, start, end);
  }

  private boolean setting(UUID accountId, String column, boolean fallback) {
    return db.sql("SELECT " + column + " FROM notification_setting WHERE account_id = :a")
        .param("a", accountId)
        .query(Boolean.class)
        .optional()
        .orElse(fallback);
  }

  private static String dedupe(String kind, UUID bookingId, String recipient, String channel) {
    return bookingId == null ? null : channel + ":" + kind + ":" + bookingId + ":" + recipient;
  }

  private static Optional<ZoneId> zone(String id) {
    try {
      return Optional.of(ZoneId.of(id));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Quiet hours, possibly over midnight. */
  static final class QuietHours {

    private QuietHours() {}

    /** {@code now}, or the end of the quiet hours it falls in. */
    static Instant next(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
      ZonedDateTime local = now.atZone(zone);
      LocalTime t = local.toLocalTime();
      boolean overMidnight = start.isAfter(end);
      boolean quiet =
          overMidnight
              ? !t.isBefore(start) || t.isBefore(end)
              : !t.isBefore(start) && t.isBefore(end);
      if (!quiet) {
        return now;
      }
      ZonedDateTime endAt = local.with(end);
      if (!endAt.isAfter(local)) {
        endAt = endAt.plusDays(1);
      }
      return endAt.toInstant();
    }
  }
}
