package care.solace.logscrub;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/**
 * Wraps the real appenders (console, OpenTelemetry) and hands them a scrubbed copy of every event:
 * message with arguments filled in, exception messages, MDC values and key-value pairs. Arguments
 * are dropped after formatting so no appender can re-read them raw.
 *
 * <pre>{@code
 * <appender name="SCRUBBED" class="care.solace.logscrub.ScrubbingAppender">
 *   <appender-ref ref="CONSOLE"/>
 * </appender>
 * }</pre>
 */
public class ScrubbingAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
    implements AppenderAttachable<ILoggingEvent> {

  private final AppenderAttachableImpl<ILoggingEvent> appenders = new AppenderAttachableImpl<>();

  @Override
  protected void append(ILoggingEvent event) {
    appenders.appendLoopOnAppenders(scrubbed(event));
  }

  static ILoggingEvent scrubbed(ILoggingEvent event) {
    ScrubbedEvent copy = new ScrubbedEvent();
    copy.setLoggerName(event.getLoggerName());
    copy.setLoggerContextRemoteView(event.getLoggerContextVO());
    copy.setLevel(event.getLevel());
    copy.setThreadName(event.getThreadName());
    copy.setInstant(event.getInstant());
    copy.setSequenceNumber(event.getSequenceNumber());
    copy.setMessage(Scrubber.scrub(event.getFormattedMessage()));
    copy.setArgumentArray(null);
    if (event.getThrowableProxy() != null) {
      copy.throwable = new ScrubbedThrowableProxy(event.getThrowableProxy());
    }
    if (event.hasCallerData()) {
      copy.setCallerData(event.getCallerData());
    }
    List<Marker> markers = event.getMarkerList();
    if (markers != null) {
      markers.forEach(copy::addMarker);
    }
    copy.setMDCPropertyMap(scrubbedMdc(event.getMDCPropertyMap()));
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs != null) {
      for (KeyValuePair pair : pairs) {
        String value = pair.value == null ? null : String.valueOf(pair.value);
        copy.addKeyValuePair(new KeyValuePair(pair.key, Scrubber.scrubField(pair.key, value)));
      }
    }
    return copy;
  }

  /** LoggingEvent only accepts its own ThrowableProxy class, so the scrubbed one is held here. */
  private static final class ScrubbedEvent extends LoggingEvent {
    private IThrowableProxy throwable;

    @Override
    public IThrowableProxy getThrowableProxy() {
      return throwable;
    }
  }

  private static Map<String, String> scrubbedMdc(Map<String, String> mdc) {
    if (mdc == null || mdc.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    mdc.forEach((key, value) -> result.put(key, Scrubber.scrubField(key, value)));
    return result;
  }

  @Override
  public void stop() {
    appenders.detachAndStopAllAppenders();
    super.stop();
  }

  @Override
  public void addAppender(Appender<ILoggingEvent> appender) {
    appenders.addAppender(appender);
  }

  @Override
  public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
    return appenders.iteratorForAppenders();
  }

  @Override
  public Appender<ILoggingEvent> getAppender(String name) {
    return appenders.getAppender(name);
  }

  @Override
  public boolean isAttached(Appender<ILoggingEvent> appender) {
    return appenders.isAttached(appender);
  }

  @Override
  public void detachAndStopAllAppenders() {
    appenders.detachAndStopAllAppenders();
  }

  @Override
  public boolean detachAppender(Appender<ILoggingEvent> appender) {
    return appenders.detachAppender(appender);
  }

  @Override
  public boolean detachAppender(String name) {
    return appenders.detachAppender(name);
  }
}
