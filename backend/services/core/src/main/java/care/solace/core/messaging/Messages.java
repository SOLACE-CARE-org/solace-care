package care.solace.core.messaging;

import care.solace.core.admin.FeatureFlags;
import care.solace.core.admin.PlatformSettings;
import care.solace.core.bookings.BookingCancelled;
import care.solace.core.bookings.BookingCompleted;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.bookings.BookingRescheduled;
import care.solace.core.catalog.Catalogue;
import care.solace.core.notifications.Delivery;
import care.solace.core.payments.PaymentNotCompleted;
import care.solace.core.payments.PaymentReceived;
import care.solace.core.payments.PayoutSettled;
import care.solace.core.payments.RefundSent;
import care.solace.core.reviews.ReviewPublished;
import care.solace.core.sessions.SeekerWaiting;
import care.solace.core.specialists.ApplicationUpdated;
import care.solace.core.specialists.LicenceRenewals;
import care.solace.jobs.Job;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Which message each event sends, to whom and by which channel (notification-copy.md; D38). Members
 * get push, guests SMS; specialists' pushes name the session language in their own language.
 */
@Component
class Messages {

  /** Guest links stay valid as long as the guest token after the session (D34, D61). */
  private static final Duration LINK_AFTER_END = Duration.ofDays(1);

  private final PlatformSettings platform;
  private final Notifier notifier;
  private final BookingRecords bookings;
  private final Catalogue catalogue;
  private final Delivery copy;
  private final FeatureFlags flags;
  private final Duration briefNotice;
  private final LicenceRenewals licences;

  Messages(
      PlatformSettings platform,
      Notifier notifier,
      BookingRecords bookings,
      Catalogue catalogue,
      Delivery copy,
      FeatureFlags flags,
      @Value("${solace.brief.notice:PT60M}") Duration briefNotice,
      LicenceRenewals licences) {
    this.platform = platform;
    this.notifier = notifier;
    this.bookings = bookings;
    this.catalogue = catalogue;
    this.copy = copy;
    this.flags = flags;
    this.briefNotice = briefNotice;
    this.licences = licences;
  }

  // --- Bookings and money -----------------------------------------------------------------------

  @ApplicationModuleListener
  void paid(PaymentReceived paid) {
    bookings
        .find(paid.bookingId())
        .ifPresent(
            b -> {
              if (b.seekerAccountId() != null) {
                UUID seeker = b.seekerAccountId();
                notifier.push(
                    seeker, "BOOKING_CONFIRMED", b.id(), false, (l, z) -> when(b.start(), l, z));
                notifier.push(
                    seeker,
                    "PAYMENT_RECEIVED",
                    b.id(),
                    false,
                    (l, z) -> new Object[] {Formats.amount(paid.amount(), l)});
                notifier.email(
                    seeker,
                    "RECEIPT",
                    b.id().toString(),
                    (l, z) ->
                        new Object[] {
                          Formats.date(Instant.now(), l, z), Formats.amount(paid.amount(), l)
                        });
              } else {
                notifier.sms(
                    b.guestContactId(),
                    "BOOKING_CONFIRMED",
                    b.id(),
                    b.end().plus(linkAfterEnd()),
                    true,
                    (l, z) -> when(b.start(), l, z));
              }
              notifier.push(
                  b.specialistId(),
                  "NEW_BOOKING",
                  b.id(),
                  false,
                  (l, z) ->
                      new Object[] {
                        Formats.date(b.start(), l, z),
                        Formats.time(b.start(), z),
                        copy.text("word." + b.modality(), l),
                        languageName(b.sessionLanguage(), l)
                      });
            });
  }

  @ApplicationModuleListener
  void notPaid(PaymentNotCompleted failed) {
    bookings
        .find(failed.bookingId())
        .ifPresent(
            b -> {
              if (b.seekerAccountId() != null) {
                notifier.push(
                    b.seekerAccountId(),
                    "PAYMENT_NOT_COMPLETED",
                    b.id(),
                    false,
                    (l, z) -> new Object[] {Formats.time(failed.payBy(), z)});
              } else {
                notifier.sms(
                    b.guestContactId(),
                    "PAYMENT_NOT_COMPLETED",
                    b.id(),
                    b.end().plus(linkAfterEnd()),
                    true,
                    (l, z) -> new Object[] {Formats.time(failed.payBy(), z)});
              }
            });
  }

  @ApplicationModuleListener
  void cancelled(BookingCancelled cancelled) {
    bookings
        .find(cancelled.bookingId())
        .ifPresent(
            b -> {
              boolean bySeeker = cancelled.cancelledBy().equals("SEEKER");
              if (bySeeker) {
                if (cancelled.refundAmount() > 0) {
                  seekerMessage(
                      b,
                      "SEEKER_CANCELLED",
                      false,
                      (l, z) -> new Object[] {Formats.amount(cancelled.refundAmount(), l)});
                }
                notifier.push(
                    b.specialistId(),
                    "SESSION_CANCELLED_BY_SEEKER",
                    b.id(),
                    false,
                    (l, z) -> when(b.start(), l, z));
              } else {
                seekerMessage(
                    b,
                    "SPECIALIST_CANCELLED",
                    true,
                    (l, z) -> new Object[] {Formats.date(b.start(), l, z)});
              }
            });
  }

  @ApplicationModuleListener
  void rescheduled(BookingRescheduled moved) {
    bookings
        .find(moved.bookingId())
        .ifPresent(
            b -> seekerMessage(b, "RESCHEDULED", false, (l, z) -> when(moved.start(), l, z)));
  }

  @ApplicationModuleListener
  void refunded(RefundSent refund) {
    bookings
        .find(refund.bookingId())
        .ifPresent(
            b ->
                seekerMessage(
                    b,
                    "REFUND_SENT",
                    false,
                    (l, z) ->
                        new Object[] {
                          Formats.amount(refund.amount(), l),
                          copy.text("word." + refund.method(), l)
                        }));
  }

  @ApplicationModuleListener
  void completed(BookingCompleted completed) {
    bookings
        .find(completed.bookingId())
        .filter(b -> b.seekerAccountId() != null)
        .ifPresent(
            b ->
                notifier.push(
                    b.seekerAccountId(), "RATE_SESSION", b.id(), true, (l, z) -> new Object[0]));
  }

  // --- Specialists ------------------------------------------------------------------------------

  @ApplicationModuleListener
  void payout(PayoutSettled payout) {
    UUID to = payout.specialistId();
    if (payout.paid()) {
      notifier.push(
          to,
          "PAYOUT_SENT",
          null,
          false,
          (l, z) ->
              new Object[] {
                Formats.amount(payout.amount(), l), copy.text("word." + payout.method(), l)
              });
      notifier.email(
          to,
          "PAYOUT_STATEMENT",
          payout.periodStart().toString(),
          (l, z) ->
              new Object[] {
                period(payout, l),
                Formats.amount(payout.amount(), l),
                copy.text("word." + payout.method(), l)
              });
    } else {
      notifier.push(to, "PAYOUT_FAILED", null, false, (l, z) -> new Object[0]);
      notifier.email(to, "PAYOUT_FAILED", payout.periodStart().toString(), (l, z) -> new Object[0]);
    }
  }

  @ApplicationModuleListener
  void review(ReviewPublished published) {
    notifier.push(published.specialistId(), "NEW_REVIEW", null, true, (l, z) -> new Object[0]);
  }

  @ApplicationModuleListener
  void application(ApplicationUpdated update) {
    UUID to = update.specialistId();
    switch (update.kind()) {
      case "SUBMITTED" -> {
        notifier.push(to, "APPLICATION_RECEIVED", null, false, (l, z) -> new Object[0]);
        notifier.email(to, "APPLICATION_RECEIVED", "submitted", (l, z) -> new Object[0]);
      }
      case "CHANGES_REQUESTED" ->
          notifier.push(to, "CHANGES_REQUESTED", null, false, (l, z) -> new Object[0]);
      case "INTERVIEW_BOOKED" -> {
        if (update.interviewAt() != null) {
          notifier.push(
              to, "INTERVIEW_BOOKED", null, false, (l, z) -> when(update.interviewAt(), l, z));
        }
      }
      case "APPROVED" -> {
        notifier.push(to, "APPROVED", null, false, (l, z) -> new Object[0]);
        notifier.email(to, "APPLICATION_DECISION", "approved", (l, z) -> new Object[0]);
      }
      case "REJECTED", "SUSPENDED" -> {
        notifier.push(to, "DECISION_MADE", null, false, (l, z) -> new Object[0]);
        notifier.email(to, "APPLICATION_DECISION", update.kind(), (l, z) -> new Object[0]);
      }
      // A profession, specialty or specialty request was decided (D62): the push never says which.
      case "PRACTICE_DECIDED" ->
          notifier.push(to, "DECISION_MADE", null, false, (l, z) -> new Object[0]);
      default -> {}
    }
  }

  @ApplicationModuleListener
  void dataCopyReady(care.solace.core.privacy.DataCopyReady ready) {
    UUID to = ready.accountId();
    String days = Long.toString(ready.days());
    notifier.push(to, "DATA_COPY_READY", null, false, (l, z) -> new Object[] {days});
    notifier.email(to, "DATA_COPY_READY", "copy-" + Instant.now(), (l, z) -> new Object[] {days});
  }

  // --- Reminders (D38) --------------------------------------------------------------------------

  /** The day before, and when joining opens 10 minutes before (D18). Each goes once. */
  @Job(
      name = "core.messaging.reminders",
      description = "Sends the day-before, joining-opens, 10-minutes-before and Brief ready pushes",
      every = "PT1M",
      initialDelay = "PT1M",
      caution = true)
  void reminders() {
    remindNow(Instant.now());
  }

  void remindNow(Instant now) {
    for (var b :
        bookings.confirmedStartingBetween(
            now.plusSeconds(23 * 3600 + 58 * 60), now.plus(Duration.ofHours(24)).plusSeconds(60))) {
      // Booked less than 24 hours ahead: the confirmation was reminder enough.
      boolean bookedLate =
          b.payBy() != null && b.payBy().isAfter(b.start().minus(Duration.ofHours(24)));
      if (b.seekerAccountId() != null
          && !bookedLate
          && notifier.sessionReminders(b.seekerAccountId())) {
        notifier.push(
            b.seekerAccountId(),
            "DAY_BEFORE",
            b.id(),
            true,
            (l, z) -> new Object[] {Formats.time(b.start(), z)});
      }
    }
    for (var b :
        bookings.confirmedStartingBetween(now.plusSeconds(8 * 60), now.plusSeconds(11 * 60))) {
      if (b.seekerAccountId() != null) {
        notifier.push(
            b.seekerAccountId(),
            "JOIN_OPENS",
            b.id(),
            false,
            (l, z) -> new Object[] {Formats.time(b.start(), z)});
      } else {
        notifier.sms(
            b.guestContactId(),
            "JOIN_OPENS",
            b.id(),
            b.end().plus(linkAfterEnd()),
            true,
            (l, z) -> new Object[] {Formats.time(b.start(), z)});
      }
      notifier.push(
          b.specialistId(),
          "SPECIALIST_SOON",
          b.id(),
          false,
          (l, z) ->
              new Object[] {languageName(b.sessionLanguage(), l), Formats.time(b.start(), z)});
    }
    // The pre-session brief, `[60]` minutes before, while AI assist is on (Phase 3, D56).
    if (flags.enabled("aiAssist")) {
      Instant at = now.plus(platform.duration("solace.brief.notice", briefNotice));
      for (var b : bookings.confirmedStartingBetween(at.minusSeconds(2 * 60), at.plusSeconds(60))) {
        notifier.push(
            b.specialistId(),
            "BRIEF_READY",
            b.id(),
            false,
            (l, z) -> new Object[] {Formats.time(b.start(), z)});
      }
    }
  }

  // --- Sessions and licences ---------------------------------------------------------------------

  /** The seeker is in the waiting room before the specialist: once per booking. */
  @ApplicationModuleListener
  void on(SeekerWaiting e) {
    notifier.push(
        e.specialistId(), "SEEKER_WAITING", e.bookingId(), false, (l, z) -> new Object[0]);
  }

  /** Every morning: licences that end within 60 days, once per licence date. */
  @Job(
      name = "core.messaging.licence-renewals",
      description = "Reminds specialists 60 days before their licence ends",
      cron = "0 0 9 * * *",
      zone = "Africa/Douala")
  void licenceRenewals() {
    licenceRenewalsNow(Instant.now());
  }

  void licenceRenewalsNow(Instant now) {
    for (var due :
        licences.due(java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("Africa/Douala")))) {
      if (!licences.markSent(due, now)) {
        continue;
      }
      Notifier.Args args =
          (l, z) ->
              new Object[] {DateTimeFormatter.ofPattern("d MMMM yyyy", l).format(due.validUntil())};
      notifier.push(due.specialistId(), "LICENCE_RENEWAL", null, false, args);
      notifier.email(due.specialistId(), "LICENCE_RENEWAL", "licence-" + due.validUntil(), args);
    }
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** Push to a member seeker, or SMS to a guest (with a link when the copy has one). */
  private void seekerMessage(
      BookingRecords.Info b, String kind, boolean smsLink, Notifier.Args args) {
    if (b.seekerAccountId() != null) {
      notifier.push(b.seekerAccountId(), kind, b.id(), false, args);
    } else {
      notifier.sms(b.guestContactId(), kind, b.id(), b.end().plus(linkAfterEnd()), smsLink, args);
    }
  }

  private static Object[] when(Instant at, Locale locale, java.time.ZoneId zone) {
    return new Object[] {Formats.date(at, locale, zone), Formats.time(at, zone)};
  }

  /** The session language's name in the recipient's language: "French", "français". */
  private String languageName(String code, Locale locale) {
    return catalogue.sessionLanguages(locale).stream()
        .filter(e -> e.code().equals(code))
        .map(Catalogue.Entry::label)
        .map(name -> "fr".equals(locale.getLanguage()) ? name.toLowerCase(Locale.FRENCH) : name)
        .findFirst()
        .orElse(code);
  }

  private static String period(PayoutSettled p, Locale locale) {
    var day = DateTimeFormatter.ofPattern("d", locale);
    var dayMonth = DateTimeFormatter.ofPattern("d MMM", Formats.language(locale));
    return day.format(p.periodStart()) + "–" + dayMonth.format(p.periodEnd());
  }

  private Duration linkAfterEnd() {
    return platform.duration("solace.bookings.guest-token-after-end", LINK_AFTER_END);
  }
}
