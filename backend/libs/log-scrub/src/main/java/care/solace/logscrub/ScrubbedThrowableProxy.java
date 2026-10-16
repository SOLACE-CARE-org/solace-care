package care.solace.logscrub;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import java.util.Arrays;

/**
 * An exception as logged, with its message and every cause's message scrubbed. Exception messages
 * often carry the values that failed: SQL parameters, request fields, contact details.
 */
final class ScrubbedThrowableProxy implements IThrowableProxy {

  private final IThrowableProxy delegate;
  private final IThrowableProxy cause;
  private final IThrowableProxy[] suppressed;

  ScrubbedThrowableProxy(IThrowableProxy delegate) {
    this.delegate = delegate;
    this.cause =
        delegate.getCause() == null ? null : new ScrubbedThrowableProxy(delegate.getCause());
    IThrowableProxy[] original = delegate.getSuppressed();
    this.suppressed =
        original == null
            ? null
            : Arrays.stream(original)
                .map(ScrubbedThrowableProxy::new)
                .toArray(IThrowableProxy[]::new);
  }

  @Override
  public String getMessage() {
    return Scrubber.scrub(delegate.getMessage());
  }

  @Override
  public String getClassName() {
    return delegate.getClassName();
  }

  @Override
  public StackTraceElementProxy[] getStackTraceElementProxyArray() {
    return delegate.getStackTraceElementProxyArray();
  }

  @Override
  public int getCommonFrames() {
    return delegate.getCommonFrames();
  }

  @Override
  public IThrowableProxy getCause() {
    return cause;
  }

  @Override
  public IThrowableProxy[] getSuppressed() {
    return suppressed;
  }

  @Override
  public boolean isCyclic() {
    return delegate.isCyclic();
  }
}
