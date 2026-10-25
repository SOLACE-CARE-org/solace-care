package care.solace.core.admin;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The settings and flags staff see on Settings and flags (admin screen 20; D42). A bracketed number
 * in the specs is a setting with that default (CLAUDE.md, Settings); a fixed decision is listed
 * too, not editable. Each default comes from Core's configuration, so the screen shows what is in
 * force.
 */
@Component
class SettingCatalogue {

  enum Group {
    MONEY,
    SESSIONS,
    REVIEWS,
    NOTIFICATIONS,
    PRIVACY,
    SAFETY,
    ACCESS,
    INSIGHT,
    TRACKING,
    AI
  }

  /** How a value is written on the screen and stored: whole units, or HH:MM. */
  enum Type {
    PERCENT,
    FCFA,
    MINUTES,
    HOURS,
    DAYS,
    COUNT,
    TIME,
    /** Whole minutes, comma-separated, each within min and max: "30,50,80" (D61). */
    MINUTES_LIST,
    /** Whole hours, likewise. */
    HOURS_LIST,
    /** A phone number in international form, or empty for none. */
    PHONE,
    /** Codes from the definition's allowed list, comma-separated; may be empty. */
    CODES;

    boolean isList() {
      return this == MINUTES_LIST || this == HOURS_LIST;
    }

    boolean isDuration() {
      return this == MINUTES || this == HOURS || this == DAYS;
    }

    Duration toDuration(long units) {
      return switch (this) {
        case MINUTES -> Duration.ofMinutes(units);
        case HOURS -> Duration.ofHours(units);
        case DAYS -> Duration.ofDays(units);
        default -> throw new IllegalStateException(name() + " is not a duration");
      };
    }

    long fromDuration(Duration d) {
      return switch (this) {
        case MINUTES -> d.toMinutes();
        case HOURS -> d.toHours();
        case DAYS -> d.toDays();
        default -> throw new IllegalStateException(name() + " is not a duration");
      };
    }
  }

  /**
   * @param fallback the default when the configuration does not set the key, as the screen writes
   *     it
   * @param editable false for fixed decisions and values the identity service holds
   */
  record Definition(
      String key,
      Group group,
      Type type,
      String fallback,
      long min,
      long max,
      boolean editable,
      List<String> allowed) {

    Definition(
        String key, Group group, Type type, String fallback, long min, long max, boolean editable) {
      this(key, group, type, fallback, min, max, editable, List.of());
    }
  }

  /**
   * @param phase the phase the feature ships in
   * @param locked not changeable from the portal
   * @param app read by the apps through {@code /v1/config}
   */
  record Flag(String key, int phase, boolean fallback, boolean locked, boolean app) {}

  private static final List<Definition> SETTINGS =
      List.of(
          // SpecServices' [n]% fee (D28), and its [Min] and [Max] prices (D27).
          new Definition(
              "solace.specialists.pricing.fee-percent",
              Group.MONEY,
              Type.PERCENT,
              "20",
              0,
              50,
              true),
          new Definition(
              "solace.specialists.pricing.min-price",
              Group.MONEY,
              Type.FCFA,
              "5000",
              0,
              1_000_000,
              true),
          new Definition(
              "solace.specialists.pricing.max-price",
              Group.MONEY,
              Type.FCFA,
              "20000",
              0,
              1_000_000,
              true),
          // Every refund, at any amount, needs a second approver (D14): fixed.
          new Definition(
              "solace.payments.refund-approvers", Group.MONEY, Type.COUNT, "2", 2, 2, false),
          // The 10-minute slot hold is a decision (CLAUDE.md, Settings).
          new Definition(
              "solace.scheduling.hold", Group.SESSIONS, Type.MINUTES, "10", 10, 10, false),
          new Definition(
              "solace.sessions.no-show-after", Group.SESSIONS, Type.MINUTES, "15", 5, 60, true),
          new Definition(
              "solace.sessions.complete-after", Group.SESSIONS, Type.MINUTES, "15", 5, 120, true),
          new Definition("solace.reviews.window", Group.REVIEWS, Type.DAYS, "14", 1, 90, true),
          new Definition(
              "solace.reviews.min-ratings-shown", Group.REVIEWS, Type.COUNT, "3", 1, 50, true),
          new Definition(
              "solace.notifications.quiet-start",
              Group.NOTIFICATIONS,
              Type.TIME,
              "21:00",
              0,
              0,
              true),
          new Definition(
              "solace.notifications.quiet-end",
              Group.NOTIFICATIONS,
              Type.TIME,
              "07:00",
              0,
              0,
              true),
          new Definition(
              "solace.privacy.export-lifetime", Group.PRIVACY, Type.DAYS, "7", 1, 30, true),
          new Definition(
              "solace.privacy.request-deadline", Group.PRIVACY, Type.DAYS, "30", 1, 90, true),
          new Definition(
              "solace.privacy.guest-retention", Group.PRIVACY, Type.DAYS, "365", 30, 3650, true),
          new Definition(
              "solace.privacy.message-retention", Group.PRIVACY, Type.DAYS, "180", 30, 3650, true),
          // A specialist reads shared tracking while a booking is live and this long after (D52).
          new Definition(
              "solace.tracking.sharing-after-session", Group.PRIVACY, Type.DAYS, "7", 0, 30, true),
          // The health check (System Design, Health check; D55): unclaimed guest responses go
          // after [30] days; one older than [90] days asks for a retake; "also a good fit" within
          // [1] point; the two price bands on its preferences screen.
          new Definition(
              "solace.health-check.unclaimed-retention",
              Group.PRIVACY,
              Type.DAYS,
              "30",
              1,
              365,
              true),
          new Definition(
              "solace.health-check.retake-after", Group.PRIVACY, Type.DAYS, "90", 7, 365, true),
          new Definition(
              "solace.health-check.also-fit-within", Group.SAFETY, Type.COUNT, "1", 0, 10, true),
          new Definition(
              "solace.health-check.price-band-1",
              Group.MONEY,
              Type.FCFA,
              "15000",
              1000,
              1000000,
              true),
          new Definition(
              "solace.health-check.price-band-2",
              Group.MONEY,
              Type.FCFA,
              "25000",
              1000,
              1000000,
              true),
          // Tranche 1 of the configuration strategy (D61): values that were in config or code.
          new Definition("solace.brief.notice", Group.SESSIONS, Type.MINUTES, "60", 15, 1440, true),
          new Definition("solace.scheduling.horizon", Group.SESSIONS, Type.DAYS, "14", 7, 90, true),
          new Definition(
              "solace.scheduling.longest-time-off", Group.SESSIONS, Type.DAYS, "60", 1, 365, true),
          new Definition(
              "solace.specialists.max-focus-areas", Group.SESSIONS, Type.COUNT, "5", 1, 20, true),
          new Definition(
              "solace.health-check.matches", Group.SESSIONS, Type.COUNT, "10", 3, 50, true),
          new Definition("solace.invites.matches", Group.SESSIONS, Type.COUNT, "5", 1, 20, true),
          new Definition(
              "solace.bookings.guest-token-after-end", Group.ACCESS, Type.DAYS, "1", 1, 7, true),
          new Definition(
              "solace.identity.unverified-signup-ttl",
              Group.ACCESS,
              Type.HOURS,
              "24",
              1,
              168,
              true),
          new Definition(
              "solace.insight.booking-drop-percent",
              Group.INSIGHT,
              Type.PERCENT,
              "50",
              10,
              90,
              true),
          new Definition(
              "solace.insight.payment-failure-factor", Group.INSIGHT, Type.COUNT, "2", 2, 10, true),
          new Definition(
              "solace.insight.min-volume", Group.INSIGHT, Type.COUNT, "5", 1, 1000, true),
          // Small-cell suppression for de-identified metrics (D58).
          new Definition("solace.insight.min-cell", Group.INSIGHT, Type.COUNT, "5", 3, 50, true),
          new Definition(
              "solace.insight.slow-payment", Group.INSIGHT, Type.MINUTES, "5", 1, 60, true),
          // Tranche 2 (D61): Tracking's and AI's values, read by them from Core over gRPC.
          new Definition(
              "solace.tracking.snooze-days", Group.TRACKING, Type.DAYS, "7", 1, 90, true),
          new Definition(
              "solace.tracking.nudges.reminder-window",
              Group.TRACKING,
              Type.HOURS,
              "2",
              1,
              12,
              true),
          new Definition(
              "solace.tracking.nudges.risk-pause", Group.TRACKING, Type.DAYS, "14", 1, 90, true),
          new Definition(
              "solace.tracking.nudges.ignored-limit", Group.TRACKING, Type.COUNT, "5", 1, 30, true),
          new Definition(
              "solace.tracking.trend-window", Group.TRACKING, Type.DAYS, "14", 7, 90, true),
          // Changes how cycles are counted: a Super admin setting (D61).
          new Definition(
              "solace.tracking.cycle.new-period-gap-days",
              Group.TRACKING,
              Type.DAYS,
              "10",
              5,
              20,
              true),
          new Definition("solace.ai.retention-days", Group.AI, Type.DAYS, "30", 1, 365, true),
          new Definition("solace.ai.eval-gate", Group.AI, Type.PERCENT, "95", 50, 100, true),
          new Definition("solace.ai.homework.default-due", Group.AI, Type.DAYS, "7", 1, 60, true),
          // Lists and text (D61, tranche 1): session lengths a specialist offers (D43 left them
          // in config until a spec said who changes them), the consents the app asks about, and
          // the number seekers call to join by phone. The weekly-hours break and notice choices
          // stay in code: the availability screen is designed with those three options each.
          new Definition(
              "solace.specialists.pricing.durations",
              Group.SESSIONS,
              Type.MINUTES_LIST,
              "30,50,80",
              15,
              120,
              true),
          new Definition(
              "solace.privacy.offered-purposes",
              Group.PRIVACY,
              Type.CODES,
              "AI_ASSIST,AI_SCRIBE",
              0,
              0,
              true,
              List.of("AI_ASSIST", "SPECIALIST_SHARING", "AI_SCRIBE")),
          new Definition(
              "solace.sessions.dial-in.number", Group.SESSIONS, Type.PHONE, "", 0, 0, true),
          // Fixed decisions, shown and not changed (CLAUDE.md, numbers without brackets);
          // FixedSettingsTest checks each one against the constant the code uses.
          new Definition(
              "solace.staff.temporary-password", Group.ACCESS, Type.HOURS, "72", 72, 72, false),
          new Definition(
              "solace.sessions.join-opens", Group.SESSIONS, Type.MINUTES, "10", 10, 10, false),
          new Definition(
              "solace.sessions.token-after-end", Group.SESSIONS, Type.MINUTES, "15", 15, 15, false),
          new Definition(
              "solace.sessions.reconnect", Group.SESSIONS, Type.MINUTES, "2", 2, 2, false),
          new Definition(
              "solace.identity.minimum-age", Group.ACCESS, Type.COUNT, "18", 18, 18, false),
          new Definition(
              "solace.specialists.licence-reminder",
              Group.SESSIONS,
              Type.DAYS,
              "60",
              60,
              60,
              false),
          // Crisis escalation at 5 and then 10 minutes is a decision (D11, D15).
          new Definition(
              "solace.crisis.escalate-after", Group.SAFETY, Type.MINUTES, "5", 5, 5, false),
          new Definition(
              "solace.guests.code-lifetime", Group.ACCESS, Type.MINUTES, "10", 5, 30, true),
          new Definition("solace.guests.code-attempts", Group.ACCESS, Type.COUNT, "3", 1, 10, true),
          // Staff idle sign-out lives in the staff realm (System Design, Security): shown, not set
          // here.
          new Definition(
              "solace.staff.idle-sign-out", Group.ACCESS, Type.MINUTES, "15", 5, 60, false));

  private static final List<Flag> FLAGS =
      List.of(
          new Flag("safetyMonitor", 1, true, true, false),
          new Flag("cardPayments", 1, false, false, true),
          // Payment providers (D61): skip a provider whose circuit is open, and try the next one
          // for a charge that certainly never reached the first.
          new Flag("paymentRouting", 1, false, false, false),
          new Flag("specialistInterviewPicker", 1, false, false, true),
          new Flag("payoutBankTransfer", 1, false, false, true),
          // An off-cycle payout would skip the two-person run (D35).
          new Flag("payoutOnRequest", 1, false, true, true),
          new Flag("onCallSpecialistCall", 1, false, false, true),
          new Flag("trackTab", 2, false, false, true),
          new Flag("healthCheck", 2, false, false, true),
          new Flag("aiAssist", 3, false, false, true),
          // Lets health data reach a non-local model: on only once Cameroon's data-transfer
          // authorisation covers it (System Design, AI; D61). AI reads it from Core.
          new Flag("aiTransferAuthorised", 3, false, false, false),
          new Flag("sessionScribe", 4, false, false, true),
          new Flag("companion", 4, false, false, true),
          new Flag("adminCopilot", 4, false, false, false),
          new Flag("phoneDialIn", 4, false, false, true));

  private final Map<String, Definition> settings = new LinkedHashMap<>();
  private final Map<String, Flag> flags = new LinkedHashMap<>();
  private final Environment environment;

  SettingCatalogue(Environment environment) {
    this.environment = environment;
    SETTINGS.forEach(d -> settings.put(d.key(), d));
    FLAGS.forEach(f -> flags.put(f.key(), f));
  }

  List<Definition> settings() {
    return List.copyOf(settings.values());
  }

  Optional<Definition> setting(String key) {
    return Optional.ofNullable(settings.get(key));
  }

  List<Flag> flags() {
    return List.copyOf(flags.values());
  }

  Optional<Flag> flag(String key) {
    return Optional.ofNullable(flags.get(key));
  }

  /** The configured default, written as the screen writes it (15, 21:00, 30,50,80). */
  String defaultValue(Definition d) {
    var binder = Binder.get(environment);
    if (d.type().isList() || d.type() == Type.CODES) {
      return binder
          .bind(
              d.key(),
              org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
          .map(v -> String.join(",", v.stream().map(String::strip).toList()))
          .orElse(d.fallback());
    }
    if (d.type() == Type.PHONE) {
      return binder.bind(d.key(), String.class).map(String::strip).orElse(d.fallback());
    }
    if (d.type().isDuration()) {
      return binder
          .bind(d.key(), Duration.class)
          .map(v -> String.valueOf(d.type().fromDuration(v)))
          .orElse(d.fallback());
    }
    if (d.type() == Type.TIME) {
      return binder.bind(d.key(), LocalTime.class).map(LocalTime::toString).orElse(d.fallback());
    }
    return binder
        .bind(d.key(), java.math.BigDecimal.class)
        .map(v -> v.stripTrailingZeros().toPlainString())
        .orElse(d.fallback());
  }

  boolean defaultEnabled(Flag f) {
    return environment.getProperty("solace.flags." + f.key(), Boolean.class, f.fallback());
  }
}
