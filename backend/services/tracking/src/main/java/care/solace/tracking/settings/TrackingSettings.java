package care.solace.tracking.settings;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.modules.StaffGate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracking settings (table {@code tracking_setting}; D61): the bracketed numbers of D51 and D52
 * that staff change in the portal. Config gives each its default; a saved value wins. Read on every
 * use, so a change applies at once on every instance.
 */
@Service
public class TrackingSettings {

  public static final String SNOOZE_DAYS = "snooze_days";
  public static final String REMINDER_WINDOW_HOURS = "nudge_reminder_window_hours";
  public static final String RISK_PAUSE_DAYS = "nudge_risk_pause_days";

  /** A setting's limits; the default comes from config. */
  public record Definition(String key, int defaultValue, int min, int max, String unit) {}

  public record Setting(
      Definition definition, int value, String updatedByName, Instant updatedAt) {}

  private final JdbcClient db;
  private final Clock clock;
  private final Map<String, Definition> definitions = new LinkedHashMap<>();

  TrackingSettings(
      JdbcClient db,
      Clock clock,
      @Value("${solace.tracking.snooze-days:7}") int snoozeDays,
      @Value("${solace.tracking.nudges.reminder-window:PT2H}") Duration reminderWindow,
      @Value("${solace.tracking.nudges.risk-pause:P14D}") Duration riskPause) {
    this.db = db;
    this.clock = clock;
    define(new Definition(SNOOZE_DAYS, snoozeDays, 1, 90, "days"));
    define(new Definition(REMINDER_WINDOW_HOURS, (int) reminderWindow.toHours(), 1, 12, "hours"));
    define(new Definition(RISK_PAUSE_DAYS, (int) riskPause.toDays(), 1, 90, "days"));
  }

  private void define(Definition d) {
    definitions.put(d.key(), d);
  }

  /** The value now: the saved one, or the default. */
  public int get(String key) {
    return setting(key).value();
  }

  public List<Setting> all() {
    return definitions.keySet().stream().map(this::setting).toList();
  }

  public Setting setting(String key) {
    Definition d = definitions.get(key);
    if (d == null) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    Optional<Setting> saved =
        db.sql("SELECT * FROM tracking_setting WHERE key = :k")
            .param("k", key)
            .query(
                (rs, n) ->
                    new Setting(
                        d,
                        Integer.parseInt(rs.getString("value")),
                        rs.getString("updated_by_name"),
                        rs.getTimestamp("updated_at").toInstant()))
            .optional();
    // A saved value outside today's limits falls back to the default rather than misbehave.
    return saved
        .filter(s -> s.value() >= d.min() && s.value() <= d.max())
        .orElse(new Setting(d, d.defaultValue(), null, null));
  }

  /** Operations or a Super admin changes a setting; the reason goes to the audit log. */
  @Transactional
  public Setting change(String key, int value, StaffGate.Staff staff) {
    if (!staff.roles().contains("OPERATIONS") && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    Definition d = definitions.get(key);
    if (d == null) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    if (value < d.min() || value > d.max()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    db.sql(
            """
            INSERT INTO tracking_setting (key, value, updated_by, updated_by_name, updated_at)
            VALUES (:k, :v, :by, :name, :now)
            ON CONFLICT (key) DO UPDATE SET value = :v, updated_by = :by,
                   updated_by_name = :name, updated_at = :now
            """)
        .param("k", key)
        .param("v", String.valueOf(value))
        .param("by", staff.id())
        .param("name", staff.name())
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return setting(key);
  }
}
