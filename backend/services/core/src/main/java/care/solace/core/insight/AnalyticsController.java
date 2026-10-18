package care.solace.core.insight;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics (admin screen 17; tag admin-insight; D46): aggregated and de-identified, counts and
 * shares only. Recommendations to booking arrive with Phase 2.
 */
@RestController
class AnalyticsController {

  enum Period {
    LAST_7_DAYS,
    LAST_30_DAYS,
    THIS_QUARTER
  }

  private final StaffAccess access;
  private final JdbcClient db;
  private final AuditLog audit;
  private final Clock clock;

  AnalyticsController(StaffAccess access, JdbcClient db, AuditLog audit, Clock clock) {
    this.access = access;
    this.db = db;
    this.audit = audit;
    this.clock = clock;
  }

  record Funnel(long searched, long choseSlot, long paid, long completed) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Analytics(
      String period,
      Instant from,
      Instant to,
      Funnel funnel,
      Double averageRating,
      Integer ratings,
      Integer bookedAgainPercent,
      long completedSessions,
      Map<String, Long> sessionsByLanguage,
      Integer recommendationsToBookingPercent) {}

  @GetMapping("/admin/v1/analytics")
  Analytics analytics(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(defaultValue = "LAST_30_DAYS") Period period) {
    access.require(token, "analytics", Level.VIEW);
    return compute(period);
  }

  @GetMapping(value = "/admin/v1/analytics/export", produces = "text/csv")
  String export(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(defaultValue = "LAST_30_DAYS") Period period) {
    access.require(token, "analytics", Level.VIEW);
    var a = compute(period);
    var csv = new StringBuilder("measure,value\n");
    csv.append("searched,").append(a.funnel().searched()).append('\n');
    csv.append("chose_slot,").append(a.funnel().choseSlot()).append('\n');
    csv.append("paid,").append(a.funnel().paid()).append('\n');
    csv.append("completed,").append(a.funnel().completed()).append('\n');
    csv.append("average_rating,")
        .append(a.averageRating() == null ? "" : a.averageRating())
        .append('\n');
    csv.append("booked_again_percent,")
        .append(a.bookedAgainPercent() == null ? "" : a.bookedAgainPercent())
        .append('\n');
    a.sessionsByLanguage()
        .forEach(
            (lang, n) -> csv.append("sessions_").append(lang).append(',').append(n).append('\n'));
    audit.adminAction("analytics.export", "analytics", period.name(), Map.of());
    return csv.toString();
  }

  private Analytics compute(Period period) {
    LocalDate today = LocalDate.now(clock.withZone(SearchCounter.DOUALA));
    LocalDate first =
        switch (period) {
          case LAST_7_DAYS -> today.minusDays(6);
          case LAST_30_DAYS -> today.minusDays(29);
          case THIS_QUARTER -> today.with(IsoFields.DAY_OF_QUARTER, 1);
        };
    Instant from = first.atStartOfDay(SearchCounter.DOUALA).toInstant();
    Instant to = today.plusDays(1).atStartOfDay(SearchCounter.DOUALA).toInstant();
    Timestamp f = Timestamp.from(from);
    Timestamp t = Timestamp.from(to);

    long searched =
        db.sql("SELECT coalesce(sum(searches), 0) FROM search_day WHERE day >= :a AND day <= :b")
            .param("a", first)
            .param("b", today)
            .query(Long.class)
            .single();
    var funnel =
        db.sql(
                """
                SELECT count(*) AS chose,
                       count(*) FILTER (WHERE confirmed_at IS NOT NULL) AS paid,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed
                  FROM booking WHERE created_at >= :f AND created_at < :t
                """)
            .param("f", f)
            .param("t", t)
            .query(
                (rs, n) ->
                    new Funnel(
                        searched, rs.getLong("chose"), rs.getLong("paid"), rs.getLong("completed")))
            .single();

    var rating =
        db.sql(
                """
                SELECT avg(stars) AS average, count(*) AS n FROM rating
                 WHERE created_at >= :f AND created_at < :t AND status <> 'FLAGGED'
                """)
            .param("f", f)
            .param("t", t)
            .query(
                (rs, n) -> {
                  int count = rs.getInt("n");
                  double avg = rs.getDouble("average");
                  return new double[] {count, Math.round(avg * 10) / 10.0};
                })
            .single();

    Map<String, Long> byLanguage = new LinkedHashMap<>();
    db.sql(
            """
            SELECT session_language, count(*) AS n FROM booking
             WHERE status = 'COMPLETED' AND starts_at >= :f AND starts_at < :t
             GROUP BY session_language ORDER BY n DESC
            """)
        .param("f", f)
        .param("t", t)
        .query(
            rs -> {
              byLanguage.put(rs.getString("session_language"), rs.getLong("n"));
            });
    long completed = byLanguage.values().stream().mapToLong(Long::longValue).sum();

    // Of seekers with a completed session in the period, how many booked again within 30 days.
    var again =
        db.sql(
                """
                WITH firsts AS (
                  SELECT coalesce(seeker_account_id, guest_contact_id) AS who, min(ends_at) AS ended
                    FROM booking
                   WHERE status = 'COMPLETED' AND starts_at >= :f AND starts_at < :t
                   GROUP BY 1)
                SELECT count(*) AS seekers,
                       count(*) FILTER (WHERE EXISTS (
                         SELECT 1 FROM booking b
                          WHERE coalesce(b.seeker_account_id, b.guest_contact_id) = firsts.who
                            AND b.confirmed_at > firsts.ended
                            AND b.confirmed_at <= firsts.ended + interval '30 days')) AS again
                  FROM firsts
                """)
            .param("f", f)
            .param("t", t)
            .query((rs, n) -> new long[] {rs.getLong("seekers"), rs.getLong("again")})
            .single();

    return new Analytics(
        period.name(),
        from,
        to,
        funnel,
        rating[0] > 0 ? rating[1] : null,
        (int) rating[0],
        again[0] > 0 ? (int) Math.round(100.0 * again[1] / again[0]) : null,
        completed,
        byLanguage,
        null);
  }
}
