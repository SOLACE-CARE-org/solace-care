package care.solace.core.admin;

import care.solace.core.admin.SettingCatalogue.Definition;
import care.solace.core.admin.SettingCatalogue.Flag;
import care.solace.core.admin.SettingCatalogue.Type;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings and flags as staff set them (D42). Values are kept in memory and reloaded every [30]
 * seconds, so a change made on one Core instance reaches the others within a minute; Tracking and
 * AI hear of it through {@code core.config.changed}. Every change keeps its version and reason.
 */
@Service
class Settings implements PlatformSettings, FeatureFlags {

  static final String CHANGED = "core.config.changed";

  record Current(String value, int version, String updatedBy, Instant updatedAt) {}

  record Version(int version, String value, String reason, String changedBy, Instant changedAt) {}

  record FlagState(boolean enabled, int version, String updatedBy, Instant updatedAt) {}

  record ConfigChanged(String kind, String key, int configVersion) {}

  private final JdbcClient db;
  private final SettingCatalogue catalogue;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private volatile Map<String, Current> values = Map.of();
  private volatile Map<String, FlagState> flags = Map.of();

  Settings(
      JdbcClient db, SettingCatalogue catalogue, ApplicationEventPublisher events, Clock clock) {
    this.db = db;
    this.catalogue = catalogue;
    this.events = events;
    this.clock = clock;
    reload();
  }

  @Scheduled(fixedDelayString = "${solace.admin.settings-refresh:PT30S}")
  void reload() {
    Map<String, Current> v = new ConcurrentHashMap<>();
    db.sql("SELECT key, value, version, updated_by_name, updated_at FROM platform_setting")
        .query(
            rs -> {
              v.put(
                  rs.getString("key"),
                  new Current(
                      rs.getString("value"),
                      rs.getInt("version"),
                      rs.getString("updated_by_name"),
                      rs.getTimestamp("updated_at").toInstant()));
            });
    Map<String, FlagState> f = new ConcurrentHashMap<>();
    db.sql("SELECT key, enabled, version, updated_by_name, updated_at FROM feature_flag")
        .query(
            rs -> {
              f.put(
                  rs.getString("key"),
                  new FlagState(
                      rs.getBoolean("enabled"),
                      rs.getInt("version"),
                      rs.getString("updated_by_name"),
                      rs.getTimestamp("updated_at").toInstant()));
            });
    values = v;
    flags = f;
  }

  // --- Reads ------------------------------------------------------------------------------------

  @Override
  public Duration duration(String key, Duration fallback) {
    var d = catalogue.setting(key).filter(x -> x.type().isDuration());
    var c = values.get(key);
    if (d.isEmpty() || c == null) {
      return fallback;
    }
    return d.get().type().toDuration(Long.parseLong(c.value()));
  }

  @Override
  public long number(String key, long fallback) {
    var c = values.get(key);
    return c == null ? fallback : new BigDecimal(c.value()).longValue();
  }

  @Override
  public BigDecimal decimal(String key, BigDecimal fallback) {
    var c = values.get(key);
    return c == null ? fallback : new BigDecimal(c.value());
  }

  @Override
  public LocalTime time(String key, LocalTime fallback) {
    var c = values.get(key);
    return c == null ? fallback : LocalTime.parse(c.value());
  }

  @Override
  public boolean enabled(String key) {
    var flag = catalogue.flag(key);
    if (flag.isEmpty()) {
      return false;
    }
    if (flag.get().locked()) {
      return catalogue.defaultEnabled(flag.get());
    }
    var state = flags.get(key);
    return state != null ? state.enabled() : catalogue.defaultEnabled(flag.get());
  }

  Optional<Current> current(String key) {
    return Optional.ofNullable(values.get(key));
  }

  Optional<FlagState> flagState(String key) {
    return Optional.ofNullable(flags.get(key));
  }

  List<Version> history(String key) {
    return db.sql(
            """
            SELECT version, value, reason, changed_by_name, changed_at
              FROM platform_setting_version WHERE key = :k ORDER BY version DESC
            """)
        .param("k", key)
        .query(
            (rs, n) ->
                new Version(
                    rs.getInt("version"),
                    rs.getString("value"),
                    rs.getString("reason"),
                    rs.getString("changed_by_name"),
                    rs.getTimestamp("changed_at").toInstant()))
        .list();
  }

  // --- Changes ----------------------------------------------------------------------------------

  /** Checks the value against the setting's type and range; returns it as stored. */
  static String normalise(Definition d, String raw) {
    String value = raw == null ? "" : raw.trim();
    if (d.type() == Type.TIME) {
      try {
        return LocalTime.parse(value).toString();
      } catch (DateTimeParseException e) {
        throw new SolaceException(AdminError.SETTING_INVALID);
      }
    }
    long n;
    try {
      n = Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new SolaceException(AdminError.SETTING_INVALID);
    }
    if (n < d.min() || n > d.max()) {
      throw new SolaceException(AdminError.SETTING_INVALID);
    }
    return Long.toString(n);
  }

  @Transactional
  Current change(String key, String raw, String reason, StaffAccess.Staff staff) {
    Definition d =
        catalogue.setting(key).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!d.editable()) {
      throw new SolaceException(AdminError.SETTING_FIXED);
    }
    String value = normalise(d, raw);
    checkPriceRange(key, value);
    Instant now = clock.instant();
    int version =
        db.sql(
                """
                INSERT INTO platform_setting (key, value, version, updated_by, updated_by_name, updated_at)
                VALUES (:k, :v, 1, :by, :name, :now)
                ON CONFLICT (key) DO UPDATE
                   SET value = EXCLUDED.value, version = platform_setting.version + 1,
                       updated_by = EXCLUDED.updated_by, updated_by_name = EXCLUDED.updated_by_name,
                       updated_at = EXCLUDED.updated_at
                RETURNING version
                """)
            .param("k", key)
            .param("v", value)
            .param("by", staff.id())
            .param("name", staff.name())
            .param("now", Timestamp.from(now))
            .query(Integer.class)
            .single();
    db.sql(
            """
            INSERT INTO platform_setting_version
                   (key, version, value, reason, changed_by, changed_by_name, changed_at)
            VALUES (:k, :ver, :v, :r, :by, :name, :now)
            """)
        .param("k", key)
        .param("ver", version)
        .param("v", value)
        .param("r", reason.trim())
        .param("by", staff.id())
        .param("name", staff.name())
        .param("now", Timestamp.from(now))
        .update();
    events.publishEvent(
        Events.platformEnvelope(CHANGED, 1, new ConfigChanged("SETTING", key, version)));
    var current = new Current(value, version, staff.name(), now);
    var next = new ConcurrentHashMap<>(values);
    next.put(key, current);
    values = next;
    return current;
  }

  /** The lowest price may not pass the highest. */
  private void checkPriceRange(String key, String value) {
    String min = "solace.specialists.pricing.min-price";
    String max = "solace.specialists.pricing.max-price";
    if (!key.equals(min) && !key.equals(max)) {
      return;
    }
    long low = key.equals(min) ? Long.parseLong(value) : effective(min);
    long high = key.equals(max) ? Long.parseLong(value) : effective(max);
    if (low > high) {
      throw new SolaceException(AdminError.SETTING_INVALID);
    }
  }

  private long effective(String key) {
    var d = catalogue.setting(key).orElseThrow();
    return Long.parseLong(current(key).map(Current::value).orElse(catalogue.defaultValue(d)));
  }

  @Transactional
  FlagState changeFlag(String key, boolean enabled, String reason, StaffAccess.Staff staff) {
    Flag f = catalogue.flag(key).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (f.locked()) {
      throw new SolaceException(AdminError.FLAG_LOCKED);
    }
    Instant now = clock.instant();
    int version =
        db.sql(
                """
                INSERT INTO feature_flag (key, enabled, version, updated_by, updated_by_name, updated_at)
                VALUES (:k, :e, 1, :by, :name, :now)
                ON CONFLICT (key) DO UPDATE
                   SET enabled = EXCLUDED.enabled, version = feature_flag.version + 1,
                       updated_by = EXCLUDED.updated_by, updated_by_name = EXCLUDED.updated_by_name,
                       updated_at = EXCLUDED.updated_at
                RETURNING version
                """)
            .param("k", key)
            .param("e", enabled)
            .param("by", staff.id())
            .param("name", staff.name())
            .param("now", Timestamp.from(now))
            .query(Integer.class)
            .single();
    db.sql(
            """
            INSERT INTO feature_flag_version
                   (key, version, enabled, reason, changed_by, changed_by_name, changed_at)
            VALUES (:k, :ver, :e, :r, :by, :name, :now)
            """)
        .param("k", key)
        .param("ver", version)
        .param("e", enabled)
        .param("r", reason.trim())
        .param("by", staff.id())
        .param("name", staff.name())
        .param("now", Timestamp.from(now))
        .update();
    events.publishEvent(
        Events.platformEnvelope(CHANGED, 1, new ConfigChanged("FLAG", key, version)));
    var state = new FlagState(enabled, version, staff.name(), now);
    var next = new ConcurrentHashMap<>(flags);
    next.put(key, state);
    flags = next;
    return state;
  }
}
