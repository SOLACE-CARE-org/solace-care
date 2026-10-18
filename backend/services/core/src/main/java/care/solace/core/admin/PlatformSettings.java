package care.solace.core.admin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * Settings staff change on Settings and flags (admin screen 20; D42). Read at the moment of use, so
 * a change reaches every Core instance within a minute. The {@code fallback} is the configured
 * value, in force until staff set one.
 */
public interface PlatformSettings {

  Duration duration(String key, Duration fallback);

  long number(String key, long fallback);

  BigDecimal decimal(String key, BigDecimal fallback);

  LocalTime time(String key, LocalTime fallback);
}
