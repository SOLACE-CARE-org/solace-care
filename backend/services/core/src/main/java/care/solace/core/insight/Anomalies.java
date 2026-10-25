package care.solace.core.insight;

import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.jobs.Job;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anomaly alerts for the admin copilot (admin screen 27; D58): every hour, the last 24 hours
 * against the 7 days before. Bookings that fall by [50]% or more, and a failed share of payments
 * [2] times the usual, each raise one alert a day once there are at least [5] of them to compare.
 */
@RestController
class Anomalies {

  private static final Logger LOG = LoggerFactory.getLogger(Anomalies.class);

  record Alert(String kind, Instant detectedAt, BigDecimal value, BigDecimal baseline) {}

  private final JdbcClient db;
  private final StaffAccess access;
  private final Clock clock;
  private final int dropPercent;
  private final double failureFactor;
  private final int minVolume;

  Anomalies(
      JdbcClient db,
      StaffAccess access,
      Clock clock,
      @Value("${solace.insight.booking-drop-percent:50}") int dropPercent,
      @Value("${solace.insight.payment-failure-factor:2}") double failureFactor,
      @Value("${solace.insight.min-volume:5}") int minVolume) {
    this.db = db;
    this.access = access;
    this.clock = clock;
    this.dropPercent = dropPercent;
    this.failureFactor = failureFactor;
    this.minVolume = minVolume;
  }

  @GetMapping("/admin/v1/insight/alerts")
  List<Alert> alerts(@AuthenticationPrincipal Jwt token) {
    access.require(token, "analytics", Level.VIEW);
    return db.sql(
            "SELECT kind, detected_at, value, baseline FROM insight_alert"
                + " WHERE detected_at >= :since ORDER BY detected_at DESC")
        .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(14))))
        .query(
            (rs, n) ->
                new Alert(
                    rs.getString("kind"),
                    rs.getTimestamp("detected_at").toInstant(),
                    rs.getBigDecimal("value"),
                    rs.getBigDecimal("baseline")))
        .list();
  }

  @Job(
      name = "core.insight.anomalies",
      description = "Checks for booking drops and failed-payment spikes for the copilot",
      every = "PT1H",
      initialDelay = "PT10M")
  void check() {
    try {
      checkNow();
    } catch (RuntimeException e) {
      LOG.warn("Checking for anomalies failed", e);
    }
  }

  void checkNow() {
    Instant now = clock.instant();
    Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofHours(24)));
    Timestamp weekBefore = Timestamp.from(now.minus(Duration.ofDays(8)));
    long recent = count("SELECT count(*) FROM booking WHERE confirmed_at >= :a", dayAgo, null);
    long before =
        count(
            "SELECT count(*) FROM booking WHERE confirmed_at >= :b AND confirmed_at < :a",
            dayAgo,
            weekBefore);
    double daily = before / 7.0;
    if (daily >= minVolume && recent <= daily * (100 - dropPercent) / 100.0) {
      raise("BOOKING_DROP", recent, daily, now);
    }
    long failedNow =
        count(
            "SELECT count(*) FROM payment WHERE status = 'FAILED' AND created_at >= :a",
            dayAgo,
            null);
    long allNow = count("SELECT count(*) FROM payment WHERE created_at >= :a", dayAgo, null);
    long failedBefore =
        count(
            "SELECT count(*) FROM payment WHERE status = 'FAILED' AND created_at >= :b"
                + " AND created_at < :a",
            dayAgo,
            weekBefore);
    long allBefore =
        count(
            "SELECT count(*) FROM payment WHERE created_at >= :b AND created_at < :a",
            dayAgo,
            weekBefore);
    if (failedNow >= minVolume && allNow > 0) {
      double share = 100.0 * failedNow / allNow;
      double usual = allBefore == 0 ? 0 : 100.0 * failedBefore / allBefore;
      if (share >= usual * failureFactor) {
        raise("PAYMENT_FAILURE_SPIKE", share, usual, now);
      }
    }
  }

  private long count(String sql, Timestamp a, Timestamp b) {
    var q = db.sql(sql).param("a", a);
    if (b != null) {
      q = q.param("b", b);
    }
    Long n = q.query(Long.class).single();
    return n == null ? 0 : n;
  }

  private void raise(String kind, double value, double baseline, Instant now) {
    db.sql(
            """
            INSERT INTO insight_alert (id, kind, day, value, baseline, detected_at)
            VALUES (:id, :k, :d, :v, :b, :now) ON CONFLICT (kind, day) DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("k", kind)
        .param("d", LocalDate.now(clock.withZone(SearchCounter.DOUALA)))
        .param("v", BigDecimal.valueOf(Math.round(value * 100) / 100.0))
        .param("b", BigDecimal.valueOf(Math.round(baseline * 100) / 100.0))
        .param("now", Timestamp.from(now))
        .update();
  }
}
