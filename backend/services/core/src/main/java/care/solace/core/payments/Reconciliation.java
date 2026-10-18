package care.solace.core.payments;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Daily reconciliation (implementation-plan.md, epic 6): every payment, refund and payout that
 * moved yesterday is checked against the provider, and any disagreement is listed for Finance.
 * Nothing is changed automatically.
 */
@Service
class Reconciliation {

  private static final Logger LOG = LoggerFactory.getLogger(Reconciliation.class);

  private final JdbcClient db;
  private final PaymentProvider provider;
  private final ZoneId zone;
  private final Clock clock;

  Reconciliation(JdbcClient db, PaymentProvider provider, PaymentSettings settings, Clock clock) {
    this.db = db;
    this.provider = provider;
    this.zone = ZoneId.of(settings.timeZone());
    this.clock = clock;
  }

  record Mismatch(String kind, UUID reference, String ours, String theirs) {}

  record Run(LocalDate day, int checked, List<Mismatch> mismatches) {}

  private record Entry(String kind, UUID id, String status, String providerRef) {}

  /** Reconciles yesterday, once. */
  void yesterday() {
    LocalDate day = LocalDate.ofInstant(clock.instant(), zone).minusDays(1);
    boolean done =
        db.sql("SELECT count(*) FROM reconciliation_run WHERE day = :d")
                .param("d", day)
                .query(Integer.class)
                .single()
            > 0;
    if (!done) {
      run(day);
    }
  }

  Run run(LocalDate day) {
    Instant from = day.atStartOfDay(zone).toInstant();
    Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
    List<Entry> entries = new ArrayList<>();
    entries.addAll(entries("PAYMENT", "payment", "created_at", from, to));
    entries.addAll(entries("REFUND", "refund", "updated_at", from, to));
    entries.addAll(entries("PAYOUT", "payout_item", "updated_at", from, to));
    List<Mismatch> mismatches = new ArrayList<>();
    for (Entry e : entries) {
      try {
        var theirs =
            e.kind().equals("PAYMENT")
                ? provider.chargeStatus(e.providerRef())
                : provider.transferStatus(e.providerRef());
        String ours = done(e.status());
        if (theirs.status() != PaymentProvider.Status.PENDING
            && !ours.equals(theirs.status().name())) {
          mismatches.add(new Mismatch(e.kind(), e.id(), e.status(), theirs.status().name()));
        }
      } catch (PaymentProvider.Unavailable ex) {
        mismatches.add(new Mismatch(e.kind(), e.id(), e.status(), "UNREACHABLE"));
        LOG.warn("Reconciliation could not reach the provider for {} {}", e.kind(), e.id(), ex);
      }
    }
    UUID runId = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO reconciliation_run (id, day, checked, created_at) VALUES (:id, :d, :c, :now)
            ON CONFLICT (day) DO NOTHING
            """)
        .param("id", runId)
        .param("d", day)
        .param("c", entries.size())
        .param("now", Timestamp.from(clock.instant()))
        .update();
    for (Mismatch m : mismatches) {
      db.sql(
              """
              INSERT INTO reconciliation_mismatch (run_id, kind, reference, ours, theirs)
              VALUES (:r, :k, :ref, :o, :t)
              """)
          .param("r", runId)
          .param("k", m.kind())
          .param("ref", m.reference())
          .param("o", m.ours())
          .param("t", m.theirs())
          .update();
    }
    return new Run(day, entries.size(), mismatches);
  }

  Optional<Run> latest() {
    return db.sql("SELECT id, day, checked FROM reconciliation_run ORDER BY day DESC LIMIT 1")
        .query(
            (rs, n) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new Run(
                  rs.getObject("day", LocalDate.class),
                  rs.getInt("checked"),
                  db.sql(
                          "SELECT kind, reference, ours, theirs FROM reconciliation_mismatch"
                              + " WHERE run_id = :r")
                      .param("r", id)
                      .query(
                          (m, i) ->
                              new Mismatch(
                                  m.getString("kind"),
                                  m.getObject("reference", UUID.class),
                                  m.getString("ours"),
                                  m.getString("theirs")))
                      .list());
            })
        .optional();
  }

  /** Our statuses in the provider's words: money arrived or left, or it did not. */
  private static String done(String ours) {
    return switch (ours) {
      case "COMPLETE", "PAID" -> "COMPLETE";
      case "FAILED" -> "FAILED";
      default -> "PENDING";
    };
  }

  private List<Entry> entries(String kind, String table, String column, Instant from, Instant to) {
    return db.sql(
            "SELECT id, status, provider_ref FROM "
                + table
                + " WHERE provider_ref IS NOT NULL AND "
                + column
                + " >= :f AND "
                + column
                + " < :t")
        .param("f", Timestamp.from(from))
        .param("t", Timestamp.from(to))
        .query(
            (rs, n) ->
                new Entry(
                    kind,
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("provider_ref")))
        .list();
  }
}
