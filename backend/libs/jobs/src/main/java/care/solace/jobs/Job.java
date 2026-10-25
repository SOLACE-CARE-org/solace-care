package care.solace.jobs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A scheduled job with a row in the service's {@code job_trigger} table (D61), in place of Spring's
 * {@code @Scheduled}. The schedule here is only the default for a new row: once the row exists, its
 * status and schedule decide. A PAUSED job is not registered when the service starts, so it does
 * not run; a change takes effect at the next restart.
 *
 * <p>Give either {@link #every()} or {@link #cron()}. Both accept {@code ${...}} placeholders.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Job {

  /** Stable name, {@code <service>.<area>.<what>}: {@code core.crisis.escalate}. */
  String name();

  /** What the job does, for staff on the portal and in logs. */
  String description();

  /** Fixed delay between runs: an ISO-8601 duration ({@code PT30S}) or milliseconds. */
  String every() default "";

  /** Delay before the first run of an {@code every} job; empty means none. */
  String initialDelay() default "";

  /** A Spring cron expression (six fields). */
  String cron() default "";

  /** The cron's time zone; empty means the server's. */
  String zone() default "";

  /**
   * Safety, money or a fixed decision depends on it: always registered, and its status and schedule
   * cannot be changed from the portal.
   */
  boolean locked() default false;
}
