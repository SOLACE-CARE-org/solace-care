package care.solace.events;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.MDC;

/** Creates envelopes with a fresh id, the current time and the current trace id. */
public final class Events {

  private static final boolean OPEN_TELEMETRY = isPresent("io.opentelemetry.api.trace.Span");
  private static final SecureRandom RANDOM = new SecureRandom();

  private static Clock clock = Clock.systemUTC();

  private Events() {}

  /**
   * An envelope for an event about one person.
   *
   * @param subjectId the person's pseudonymous id; never a name, phone or email
   * @param locale their app language, {@code en} or {@code fr}
   */
  public static EventEnvelope envelope(
      String type, int version, String subjectId, String locale, Object payload) {
    return new EventEnvelope(
        UUID.randomUUID(), type, version, clock.instant(), subjectId, locale, traceId(), payload);
  }

  /** An envelope for a platform event that is about no one. */
  public static EventEnvelope platformEnvelope(String type, int version, Object payload) {
    return envelope(type, version, null, null, payload);
  }

  /**
   * The current trace id, so the event joins the trace of the request that caused it. A new one
   * when there is no trace (scheduled jobs), because the schema requires one.
   */
  static String traceId() {
    if (OPEN_TELEMETRY) {
      String fromSpan = OpenTelemetryTrace.currentTraceId();
      if (fromSpan != null) {
        return fromSpan;
      }
    }
    String fromMdc = MDC.get("traceId");
    if (fromMdc != null && EventEnvelope.TRACE_ID.matcher(fromMdc).matches()) {
      return fromMdc;
    }
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** For tests. */
  static void useClock(Clock testClock) {
    clock = testClock;
  }

  private static boolean isPresent(String className) {
    try {
      Class.forName(className, false, Events.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private static final class OpenTelemetryTrace {
    private OpenTelemetryTrace() {}

    static String currentTraceId() {
      io.opentelemetry.api.trace.SpanContext context =
          io.opentelemetry.api.trace.Span.current().getSpanContext();
      return context.isValid() ? context.getTraceId() : null;
    }
  }
}
