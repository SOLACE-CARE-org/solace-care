package care.solace.coresettings;

import care.solace.contracts.core.v1.ConfigServiceGrpc;
import care.solace.contracts.core.v1.GetPlatformConfigRequest;
import care.solace.contracts.core.v1.GetPlatformConfigResponse;
import care.solace.events.EventEnvelope;
import care.solace.grpc.ResilientCalls;
import care.solace.jobs.Job;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * The platform settings and flags this service takes from Core (D61; System Design, Admin &
 * config): Core owns every setting, so Tracking and AI have no settings table of their own.
 *
 * <ul>
 *   <li>Loaded when the service starts, again when core.config.changed arrives, and every [5]
 *       minutes in case an event was missed.
 *   <li>A key staff never set keeps the service's own configured value: the {@code fallback} every
 *       read passes.
 *   <li>When Core cannot be reached, the values last loaded stay in force; the service never falls
 *       back silently to a default that differs from what staff set while it has them.
 * </ul>
 *
 * Reloading is idempotent, so a repeated event changes nothing.
 */
public class CoreSettings {

  private static final Logger LOG = LoggerFactory.getLogger(CoreSettings.class);

  private record Value(String value, String unit) {}

  private final ConfigServiceGrpc.ConfigServiceBlockingStub core;
  private final ResilientCalls calls;
  private volatile Map<String, Value> settings = Map.of();
  private volatile Map<String, Boolean> flags = Map.of();
  private volatile boolean loaded;

  public CoreSettings(ConfigServiceGrpc.ConfigServiceBlockingStub core, ResilientCalls calls) {
    this.core = core;
    this.calls = calls;
  }

  /** A duration staff set (minutes, hours or days), or {@code fallback}. */
  public Duration duration(String key, Duration fallback) {
    var v = settings.get(key);
    if (v == null) {
      return fallback;
    }
    long n = Long.parseLong(v.value());
    return switch (v.unit()) {
      case "MINUTES" -> Duration.ofMinutes(n);
      case "HOURS" -> Duration.ofHours(n);
      case "DAYS" -> Duration.ofDays(n);
      default -> fallback;
    };
  }

  /** A whole number staff set (a count, a percent, days as a number), or {@code fallback}. */
  public long number(String key, long fallback) {
    var v = settings.get(key);
    return v == null ? fallback : Long.parseLong(v.value());
  }

  /** A flag as Core has it, or {@code fallback} before Core was ever reached. */
  public boolean enabled(String key, boolean fallback) {
    return flags.getOrDefault(key, fallback);
  }

  /** Whether Core answered at least once since this service started. */
  public boolean loaded() {
    return loaded;
  }

  @Job(
      name = "${spring.application.name:service}.settings.reload",
      description = "Reloads the platform settings and flags from Core",
      every = "${solace.core-settings.refresh:PT5M}",
      caution = true)
  public void reload() {
    GetPlatformConfigResponse response =
        calls.call(
            "core",
            () -> core.getPlatformConfig(GetPlatformConfigRequest.getDefaultInstance()),
            e -> null);
    if (response == null) {
      LOG.warn("Core settings not reloaded; the values last loaded stay in force");
      return;
    }
    Map<String, Value> s = new HashMap<>();
    response
        .getSettingsList()
        .forEach(p -> s.put(p.getKey(), new Value(p.getValue(), p.getUnit())));
    Map<String, Boolean> f = new HashMap<>();
    response.getFlagsList().forEach(p -> f.put(p.getKey(), p.getEnabled()));
    settings = Map.copyOf(s);
    flags = Map.copyOf(f);
    loaded = true;
  }

  /**
   * Every instance must hear every change, so each one listens in a group of its own, from the
   * newest event on.
   */
  @KafkaListener(
      topics = "core.config",
      groupId = "${spring.application.name:service}.core-settings.${random.uuid}",
      properties = "auto.offset.reset=latest")
  void changed(EventEnvelope event) {
    if ("core.config.changed".equals(event.type())) {
      reload();
    }
  }
}
