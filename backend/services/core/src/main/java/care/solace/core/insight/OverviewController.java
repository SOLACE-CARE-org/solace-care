package care.solace.core.insight;

import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.bookings.BookingAttendance;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.specialists.SpecialistOffers;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live overview (admin screen 2; tag admin-insight; D46): today's numbers in Douala time, the
 * sessions happening now, what needs attention and how the services behind the app are doing.
 */
@RestController
class OverviewController {

  /** A payment still unconfirmed this long counts as slow mobile money. Not in the specs. */
  private static final Duration SLOW_PAYMENT = Duration.ofMinutes(5);

  private static final Duration CALLS_CHECK = Duration.ofSeconds(60);

  private final StaffAccess access;
  private final JdbcClient db;
  private final SpecialistOffers specialists;
  private final BookingAttendance attendance;
  private final ObjectProvider<CircuitBreakerRegistry> breakers;
  private final URI livekit;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();
  private volatile Instant callsCheckedAt = Instant.EPOCH;
  private volatile String callsStatus = "UNKNOWN";

  OverviewController(
      StaffAccess access,
      JdbcClient db,
      SpecialistOffers specialists,
      BookingAttendance attendance,
      ObjectProvider<CircuitBreakerRegistry> breakers,
      @Value("${solace.sessions.api-url:http://localhost:7880}") URI livekit,
      Clock clock) {
    this.access = access;
    this.db = db;
    this.specialists = specialists;
    this.attendance = attendance;
    this.breakers = breakers;
    this.livekit = livekit;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Crisis(int unacknowledged, Instant oldestAt, String onCall) {}

  record InSession(int total, int video, int audio) {}

  record SessionsToday(int total, int completed, int noShows) {}

  record BookingsToday(int total, Map<String, Integer> byLanguage) {}

  record PaymentsToday(long captured, int failed) {}

  record SpecialistRef(UUID id, String fullName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SessionRow(
      UUID id,
      String reference,
      Instant startsAt,
      Instant endsAt,
      SpecialistRef specialist,
      String personRef,
      String modality,
      String sessionLanguage,
      long price,
      String status,
      String waiting,
      boolean disputed) {}

  record Attention(int applications, int refunds, int reviews, int disputes, int dataRequestsDue) {}

  record Health(String service, String status) {}

  record Overview(
      Instant at,
      Crisis crisis,
      InSession inSession,
      SessionsToday sessionsToday,
      BookingsToday bookingsToday,
      PaymentsToday paymentsToday,
      List<SessionRow> sessionsNow,
      Attention attention,
      List<Health> health) {}

  @GetMapping("/admin/v1/overview")
  Overview overview(@AuthenticationPrincipal Jwt token, Locale locale) {
    access.require(token, "overview", Level.VIEW);
    Instant now = clock.instant();
    LocalDate today = LocalDate.now(clock.withZone(SearchCounter.DOUALA));
    Timestamp start = Timestamp.from(today.atStartOfDay(SearchCounter.DOUALA).toInstant());
    Timestamp end =
        Timestamp.from(today.plusDays(1).atStartOfDay(SearchCounter.DOUALA).toInstant());
    Timestamp at = Timestamp.from(now);

    var crisis =
        db.sql(
                """
                SELECT count(*) AS n, min(raised_at) AS oldest FROM crisis_alert WHERE status = 'OPEN'
                """)
            .query(
                (rs, n) -> {
                  Timestamp oldest = rs.getTimestamp("oldest");
                  return new Crisis(
                      rs.getInt("n"), oldest == null ? null : oldest.toInstant(), onCall(at));
                })
            .single();

    var inSession =
        db.sql(
                """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE modality = 'VIDEO') AS video,
                       count(*) FILTER (WHERE modality = 'AUDIO') AS audio
                  FROM booking WHERE status = 'IN_PROGRESS'
                """)
            .query(
                (rs, n) ->
                    new InSession(rs.getInt("total"), rs.getInt("video"), rs.getInt("audio")))
            .single();

    var sessions =
        db.sql(
                """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows
                  FROM booking
                 WHERE starts_at >= :s AND starts_at < :e
                   AND status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'NO_SHOW')
                """)
            .param("s", start)
            .param("e", end)
            .query(
                (rs, n) ->
                    new SessionsToday(
                        rs.getInt("total"), rs.getInt("completed"), rs.getInt("no_shows")))
            .single();

    Map<String, Integer> byLanguage = new LinkedHashMap<>();
    db.sql(
            """
            SELECT session_language, count(*) AS n FROM booking
             WHERE confirmed_at >= :s AND confirmed_at < :e
             GROUP BY session_language ORDER BY n DESC
            """)
        .param("s", start)
        .param("e", end)
        .query(
            rs -> {
              byLanguage.put(rs.getString("session_language"), rs.getInt("n"));
            });
    var bookings =
        new BookingsToday(
            byLanguage.values().stream().mapToInt(Integer::intValue).sum(), byLanguage);

    var payments =
        db.sql(
                """
                SELECT coalesce(sum(amount) FILTER (WHERE status = 'COMPLETE'), 0) AS captured,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed
                  FROM payment WHERE created_at >= :s AND created_at < :e
                """)
            .param("s", start)
            .param("e", end)
            .query((rs, n) -> new PaymentsToday(rs.getLong("captured"), rs.getInt("failed")))
            .single();

    return new Overview(
        now,
        crisis,
        inSession,
        sessions,
        bookings,
        payments,
        sessionsNow(at, Timestamp.from(now.plus(Duration.ofMinutes(30))), locale),
        attention(at, Timestamp.from(now.plus(Duration.ofDays(7)))),
        health(at));
  }

  private String onCall(Timestamp at) {
    return db.sql(
            """
            SELECT name FROM crisis_rota
             WHERE role = 'PRIMARY' AND starts_at <= :at AND ends_at > :at
             ORDER BY starts_at DESC LIMIT 1
            """)
        .param("at", at)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private List<SessionRow> sessionsNow(Timestamp now, Timestamp soon, Locale locale) {
    record Row(
        UUID id,
        UUID seeker,
        UUID guest,
        UUID specialist,
        Instant start,
        Instant end,
        String modality,
        String language,
        long price,
        String status,
        boolean disputed) {}
    var rows =
        db.sql(
                """
                SELECT b.id, b.seeker_account_id, b.guest_contact_id, b.specialist_id, b.starts_at,
                       b.ends_at, b.modality, b.session_language, b.price, b.status,
                       EXISTS (SELECT 1 FROM booking_dispute d
                                WHERE d.booking_id = b.id AND d.resolved_at IS NULL)
                       OR EXISTS (SELECT 1 FROM session_flag f
                                   WHERE f.booking_id = b.id AND f.resolved_at IS NULL) AS disputed
                  FROM booking b
                 WHERE b.status = 'IN_PROGRESS'
                    OR (b.status = 'CONFIRMED' AND b.starts_at < :soon AND b.ends_at > :now)
                 ORDER BY b.starts_at LIMIT 50
                """)
            .param("now", now)
            .param("soon", soon)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("seeker_account_id", UUID.class),
                        rs.getObject("guest_contact_id", UUID.class),
                        rs.getObject("specialist_id", UUID.class),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getString("modality"),
                        rs.getString("session_language"),
                        rs.getLong("price"),
                        rs.getString("status"),
                        rs.getBoolean("disputed")))
            .list();
    var waiting = attendance.waiting(rows.stream().map(Row::id).toList());
    Map<UUID, String> names = new HashMap<>();
    var out = new ArrayList<SessionRow>();
    for (var r : rows) {
      UUID subject = r.seeker() != null ? r.seeker() : r.guest();
      out.add(
          new SessionRow(
              r.id(),
              "BK-" + r.id().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT),
              r.start(),
              r.end(),
              new SpecialistRef(
                  r.specialist(),
                  names.computeIfAbsent(
                      r.specialist(),
                      id ->
                          specialists
                              .summary(id, locale)
                              .map(SpecialistOffers.Summary::fullName)
                              .orElse(""))),
              BookingRecords.personRef(subject, r.seeker() == null),
              r.modality(),
              r.language(),
              r.price(),
              r.status(),
              waiting.get(r.id()),
              r.disputed()));
    }
    return out;
  }

  private Attention attention(Timestamp now, Timestamp week) {
    return db.sql(
            """
            SELECT
              (SELECT count(*) FROM specialist_profile
                WHERE verification_status IN ('SUBMITTED', 'UNDER_REVIEW')) AS applications,
              (SELECT count(*) FROM refund WHERE status = 'PROPOSED') AS refunds,
              (SELECT count(*) FROM rating WHERE status = 'PENDING') AS reviews,
              (SELECT count(DISTINCT booking_id) FROM (
                  SELECT booking_id FROM booking_dispute WHERE resolved_at IS NULL
                  UNION SELECT booking_id FROM session_flag WHERE resolved_at IS NULL) d) AS disputes,
              (SELECT count(*) FROM data_request
                WHERE status IN ('PREPARING', 'REQUESTED') AND due_at < :week) AS due
            """)
        .param("week", week)
        .query(
            (rs, n) ->
                new Attention(
                    rs.getInt("applications"),
                    rs.getInt("refunds"),
                    rs.getInt("reviews"),
                    rs.getInt("disputes"),
                    rs.getInt("due")))
        .single();
  }

  private List<Health> health(Timestamp now) {
    var out = new ArrayList<Health>();
    out.add(new Health("CORE", "HEALTHY"));
    out.add(new Health("AI", breaker("ai")));
    out.add(new Health("CALLS", calls()));
    int slow =
        db.sql("SELECT count(*) FROM payment WHERE status = 'PENDING' AND created_at < :before")
            .param("before", Timestamp.from(now.toInstant().minus(SLOW_PAYMENT)))
            .query(Integer.class)
            .single();
    out.add(new Health("PAYMENTS", slow > 0 ? "SLOW" : "HEALTHY"));
    int failedSms =
        db.sql(
                """
                SELECT count(*) FROM notification
                 WHERE channel = 'SMS' AND status = 'FAILED' AND created_at > :since
                """)
            .param("since", Timestamp.from(now.toInstant().minus(Duration.ofHours(1))))
            .query(Integer.class)
            .single();
    out.add(new Health("SMS", failedSms > 0 ? "SLOW" : "HEALTHY"));
    return out;
  }

  /** The gRPC circuit breaker's state (System Design: a breaker on every call). */
  private String breaker(String name) {
    CircuitBreakerRegistry registry = breakers.getIfAvailable();
    if (registry == null) {
      return "UNKNOWN";
    }
    return registry
        .find(name)
        .map(
            b ->
                b.getState() == CircuitBreaker.State.OPEN
                    ? "DOWN"
                    : b.getState() == CircuitBreaker.State.HALF_OPEN ? "SLOW" : "HEALTHY")
        .orElse("HEALTHY");
  }

  /** The media server answers HTTP on its API port; checked at most once a minute. */
  private String calls() {
    Instant now = clock.instant();
    if (callsCheckedAt.plus(CALLS_CHECK).isAfter(now)) {
      return callsStatus;
    }
    callsCheckedAt = now;
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(livekit).timeout(Duration.ofMillis(1500)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      callsStatus = response.statusCode() < 500 ? "HEALTHY" : "DOWN";
    } catch (java.io.IOException e) {
      callsStatus = "DOWN";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      callsStatus = "UNKNOWN";
    }
    return callsStatus;
  }
}
