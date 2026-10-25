package care.solace.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** The {@code job_trigger} rows of this service's database, and their versions. */
public class JobTriggerStore {

  public enum Status {
    ACTIVE,
    PAUSED
  }

  public enum Kind {
    CRON,
    INTERVAL
  }

  /**
   * @param schedule a cron expression for CRON, an ISO-8601 duration for INTERVAL
   * @param zone the cron's time zone; null for INTERVAL or the server's zone
   * @param registeredVersion the version the running service registered at its last start; null
   *     when it did not register the job. Differs from {@code version} until the next restart
   */
  public record Trigger(
      String name,
      String description,
      Status status,
      boolean caution,
      Kind kind,
      String schedule,
      String zone,
      int version,
      String updatedByName,
      Instant updatedAt,
      Integer registeredVersion,
      Instant lastStartedAt,
      Instant lastFinishedAt,
      String lastOutcome,
      Instant nextRunAt) {

    /** Saved, but not what the running service does until it restarts. */
    public boolean pendingRestart() {
      boolean registered = registeredVersion != null;
      return registered != (status == Status.ACTIVE)
          || (registered && registeredVersion != version);
    }
  }

  public record Version(
      int version,
      Status status,
      Kind kind,
      String schedule,
      String zone,
      String reason,
      String changedBy,
      Instant changedAt) {}

  private final JdbcTemplate jdbc;

  public JobTriggerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Adds the row with the code's defaults if it is missing, and keeps the description and the
   * caution in step with the code, which decides both.
   */
  void ensure(
      String name,
      String description,
      boolean caution,
      Kind kind,
      String schedule,
      String zone,
      Instant now) {
    int added =
        jdbc.update(
            """
            INSERT INTO job_trigger (name, description, status, caution, kind, schedule, zone,
                                     version, updated_by, updated_by_name, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, 1, 'system', 'System', ?)
            ON CONFLICT (name) DO NOTHING
            """,
            name,
            description,
            caution,
            kind.name(),
            schedule,
            zone,
            Timestamp.from(now));
    if (added == 1) {
      jdbc.update(
          """
          INSERT INTO job_trigger_version (name, version, status, kind, schedule, zone, reason,
                                           changed_by, changed_by_name, changed_at)
          VALUES (?, 1, 'ACTIVE', ?, ?, ?, 'Default from the code', 'system', 'System', ?)
          """,
          name,
          kind.name(),
          schedule,
          zone,
          Timestamp.from(now));
    } else {
      jdbc.update(
          "UPDATE job_trigger SET description = ?, caution = ? WHERE name = ?",
          description,
          caution,
          name);
    }
  }

  void registered(String name, Integer version) {
    jdbc.update("UPDATE job_trigger SET registered_version = ? WHERE name = ?", version, name);
  }

  void started(String name, Instant at) {
    jdbc.update(
        "UPDATE job_trigger SET last_started_at = ? WHERE name = ?", Timestamp.from(at), name);
  }

  void finished(String name, boolean ok, Instant at, Instant next) {
    jdbc.update(
        """
        UPDATE job_trigger SET last_finished_at = ?, last_outcome = ?, next_run_at = ?
         WHERE name = ?
        """,
        Timestamp.from(at),
        ok ? "OK" : "FAILED",
        next == null ? null : Timestamp.from(next),
        name);
  }

  public List<Trigger> all() {
    return jdbc.query("SELECT * FROM job_trigger ORDER BY name", JobTriggerStore::trigger);
  }

  public Optional<Trigger> find(String name) {
    return jdbc
        .query("SELECT * FROM job_trigger WHERE name = ?", JobTriggerStore::trigger, name)
        .stream()
        .findFirst();
  }

  /** Writes a new version over {@code from}; false when someone changed it meanwhile. */
  boolean change(
      String name,
      int from,
      Status status,
      Kind kind,
      String schedule,
      String zone,
      String reason,
      String by,
      String byName,
      Instant now) {
    int done =
        jdbc.update(
            """
            UPDATE job_trigger SET status = ?, kind = ?, schedule = ?, zone = ?, version = version + 1,
                   updated_by = ?, updated_by_name = ?, updated_at = ?
             WHERE name = ? AND version = ?
            """,
            status.name(),
            kind.name(),
            schedule,
            zone,
            by,
            byName,
            Timestamp.from(now),
            name,
            from);
    if (done != 1) {
      return false;
    }
    jdbc.update(
        """
        INSERT INTO job_trigger_version (name, version, status, kind, schedule, zone, reason,
                                         changed_by, changed_by_name, changed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        name,
        from + 1,
        status.name(),
        kind.name(),
        schedule,
        zone,
        reason,
        by,
        byName,
        Timestamp.from(now));
    return true;
  }

  public List<Version> versions(String name) {
    return jdbc.query(
        "SELECT * FROM job_trigger_version WHERE name = ? ORDER BY version DESC",
        (rs, n) ->
            new Version(
                rs.getInt("version"),
                Status.valueOf(rs.getString("status")),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("schedule"),
                rs.getString("zone"),
                rs.getString("reason"),
                rs.getString("changed_by_name"),
                rs.getTimestamp("changed_at").toInstant()),
        name);
  }

  private static Trigger trigger(ResultSet rs, int n) throws SQLException {
    return new Trigger(
        rs.getString("name"),
        rs.getString("description"),
        Status.valueOf(rs.getString("status")),
        rs.getBoolean("caution"),
        Kind.valueOf(rs.getString("kind")),
        rs.getString("schedule"),
        rs.getString("zone"),
        rs.getInt("version"),
        rs.getString("updated_by_name"),
        rs.getTimestamp("updated_at").toInstant(),
        (Integer) rs.getObject("registered_version"),
        instant(rs, "last_started_at"),
        instant(rs, "last_finished_at"),
        rs.getString("last_outcome"),
        instant(rs, "next_run_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
