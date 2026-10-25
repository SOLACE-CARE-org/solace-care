package care.solace.core.insight;

import care.solace.contracts.core.v1.Metric;
import care.solace.contracts.core.v1.MetricRow;
import care.solace.contracts.core.v1.MetricsServiceGrpc;
import care.solace.contracts.core.v1.Period;
import care.solace.contracts.core.v1.QueryMetricRequest;
import care.solace.contracts.core.v1.QueryMetricResponse;
import care.solace.core.admin.PlatformSettings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One aggregated, de-identified metric for the admin copilot (metrics_service.proto; admin screen
 * 27; D58). A fixed catalogue of grouped counts, never a person and never free text; a count under
 * the minimum cell size ([5]) shows as "<5" so no small group can be singled out.
 */
@Service
class MetricsEndpoint extends MetricsServiceGrpc.MetricsServiceImplBase {

  private final PlatformSettings platform;
  private final JdbcClient db;
  private final MessageSource messages;
  private final int configuredMinCell;
  private final Clock clock;

  MetricsEndpoint(
      PlatformSettings platform,
      JdbcClient db,
      MessageSource messages,
      @Value("${solace.insight.min-cell:5}") int minCell,
      Clock clock) {
    this.platform = platform;
    this.db = db;
    this.messages = messages;
    this.configuredMinCell = minCell;
    this.clock = clock;
  }

  private record Group(String label, long a, long b) {}

  @Override
  public void queryMetric(QueryMetricRequest request, StreamObserver<QueryMetricResponse> out) {
    if (request.getMetric() == Metric.METRIC_UNSPECIFIED
        || request.getMetric() == Metric.UNRECOGNIZED
        || request.getPeriod() == Period.PERIOD_UNSPECIFIED
        || request.getPeriod() == Period.UNRECOGNIZED) {
      out.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    Locale locale = "fr".equals(request.getLocale()) ? Locale.FRENCH : Locale.ENGLISH;
    Instant[] range = range(request.getPeriod());
    Timestamp f = Timestamp.from(range[0]);
    Timestamp t = Timestamp.from(range[1]);
    String name = request.getMetric().name().replace("METRIC_", "");
    List<String> columns = new ArrayList<>();
    List<Group> groups;
    boolean twoCounts = false;
    switch (request.getMetric()) {
      case METRIC_BOOKINGS_BY_SOURCE ->
          groups =
              grouped(
                  "SELECT source AS g, count(*) AS a, 0 AS b FROM booking"
                      + " WHERE confirmed_at >= :f AND confirmed_at < :t GROUP BY 1 ORDER BY 2 DESC",
                  f,
                  t,
                  g -> text("metrics.source." + g, locale, g));
      case METRIC_INVITES_BY_MODULE -> {
        twoCounts = true;
        groups =
            grouped(
                """
                SELECT split_part(i.rule_code, '.', 1) AS g, count(*) AS a,
                       count(b.id) FILTER (WHERE b.confirmed_at IS NOT NULL) AS b
                  FROM tracking_invite i LEFT JOIN booking b ON b.recommendation_id = i.recommendation_id
                 WHERE i.created_at >= :f AND i.created_at < :t
                 GROUP BY 1 ORDER BY 2 DESC
                """,
                f,
                t,
                g -> text("metrics.module." + g, locale, g));
      }
      case METRIC_SESSIONS_BY_LANGUAGE ->
          groups =
              grouped(
                  "SELECT session_language AS g, count(*) AS a, 0 AS b FROM booking"
                      + " WHERE status = 'COMPLETED' AND starts_at >= :f AND starts_at < :t"
                      + " GROUP BY 1 ORDER BY 2 DESC",
                  f,
                  t,
                  g -> {
                    String n = Locale.forLanguageTag(g).getDisplayLanguage(locale);
                    return n.isBlank() ? g : n.substring(0, 1).toUpperCase(locale) + n.substring(1);
                  });
      case METRIC_SESSIONS_BY_MODALITY ->
          groups =
              grouped(
                  "SELECT modality AS g, count(*) AS a, 0 AS b FROM booking"
                      + " WHERE status = 'COMPLETED' AND starts_at >= :f AND starts_at < :t"
                      + " GROUP BY 1 ORDER BY 2 DESC",
                  f,
                  t,
                  g -> text("metrics.modality." + g, locale, g));
      case METRIC_PAYMENTS_BY_STATUS ->
          groups =
              grouped(
                  "SELECT status AS g, count(*) AS a, 0 AS b FROM payment"
                      + " WHERE created_at >= :f AND created_at < :t GROUP BY 1 ORDER BY 2 DESC",
                  f,
                  t,
                  g -> text("metrics.payment." + g, locale, g));
      case METRIC_BOOKINGS_BY_STATUS ->
          groups =
              grouped(
                  "SELECT status AS g, count(*) AS a, 0 AS b FROM booking"
                      + " WHERE created_at >= :f AND created_at < :t GROUP BY 1 ORDER BY 2 DESC",
                  f,
                  t,
                  g -> text("metrics.status." + g, locale, g));
      case METRIC_RATINGS -> {
        var r =
            db.sql(
                    "SELECT count(*) AS n, coalesce(avg(stars), 0) AS avg FROM rating"
                        + " WHERE created_at >= :f AND created_at < :t AND status <> 'FLAGGED'")
                .param("f", f)
                .param("t", t)
                .query((rs, n) -> new double[] {rs.getLong("n"), rs.getDouble("avg")})
                .single();
        columns.add(text("metrics.col.ratings", locale, "Ratings"));
        columns.add(text("metrics.col.average", locale, "Average"));
        boolean small = r[0] > 0 && r[0] < minCell();
        var row =
            MetricRow.newBuilder()
                .addCells(small ? "<" + minCell() : Long.toString((long) r[0]))
                .addCells(
                    small || r[0] == 0
                        ? "–"
                        : String.format(locale, "%.1f", Math.round(r[1] * 10) / 10.0));
        out.onNext(
            QueryMetricResponse.newBuilder()
                .addAllColumns(columns)
                .addRows(row)
                .setSource(text("metrics.source-line." + name, locale, name))
                .setFrom(range[0].toString())
                .setTo(range[1].toString())
                .setSuppressed(small)
                .build());
        out.onCompleted();
        return;
      }
      default -> {
        out.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        return;
      }
    }
    columns.add(text("metrics.col.group." + name, locale, name));
    columns.add(text("metrics.col.a." + name, locale, "Count"));
    if (twoCounts) {
      columns.add(text("metrics.col.b." + name, locale, "Count"));
    }
    boolean suppressed = false;
    var reply = QueryMetricResponse.newBuilder().addAllColumns(columns);
    for (Group g : groups) {
      var row = MetricRow.newBuilder().addCells(g.label());
      for (long v : twoCounts ? new long[] {g.a(), g.b()} : new long[] {g.a()}) {
        if (v > 0 && v < minCell()) {
          suppressed = true;
          row.addCells("<" + minCell());
        } else {
          row.addCells(Long.toString(v));
        }
      }
      reply.addRows(row);
    }
    out.onNext(
        reply
            .setSource(text("metrics.source-line." + name, locale, name))
            .setFrom(range[0].toString())
            .setTo(range[1].toString())
            .setSuppressed(suppressed)
            .build());
    out.onCompleted();
  }

  private List<Group> grouped(
      String sql, Timestamp f, Timestamp t, java.util.function.Function<String, String> label) {
    return db.sql(sql)
        .param("f", f)
        .param("t", t)
        .query(
            (rs, n) -> new Group(label.apply(rs.getString("g")), rs.getLong("a"), rs.getLong("b")))
        .list();
  }

  /** The period in Douala time: [first instant, the instant after the last). */
  Instant[] range(Period period) {
    LocalDate today = LocalDate.now(clock.withZone(SearchCounter.DOUALA));
    LocalDate first;
    LocalDate end = today.plusDays(1);
    switch (period) {
      case PERIOD_LAST_7_DAYS -> first = today.minusDays(6);
      case PERIOD_LAST_MONTH -> {
        first = today.withDayOfMonth(1).minusMonths(1);
        end = today.withDayOfMonth(1);
      }
      case PERIOD_THIS_QUARTER -> first = today.with(IsoFields.DAY_OF_QUARTER, 1);
      default -> first = today.minusDays(29);
    }
    return new Instant[] {
      first.atStartOfDay(SearchCounter.DOUALA).toInstant(),
      end.atStartOfDay(SearchCounter.DOUALA).toInstant()
    };
  }

  private String text(String key, Locale locale, String fallback) {
    return messages.getMessage(key, null, fallback, locale);
  }

  /** Cells under this are hidden (D58); a setting, read when used. */
  private long minCell() {
    return platform.number("solace.insight.min-cell", configuredMinCell);
  }
}
