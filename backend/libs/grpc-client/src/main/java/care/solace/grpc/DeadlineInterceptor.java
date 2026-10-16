package care.solace.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Gives every outgoing call the 2-second deadline (D2, CLAUDE.md: a fixed number, not a setting). A
 * call that already has a shorter deadline keeps it; a longer one is cut to 2 seconds.
 */
public class DeadlineInterceptor implements ClientInterceptor {

  /** gRPC is only used while a person waits for the answer, so no call may take longer. */
  public static final Duration DEADLINE = Duration.ofSeconds(2);

  @Override
  public <Q, R> ClientCall<Q, R> interceptCall(
      MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
    CallOptions limited = options.withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    if (options.getDeadline() != null && options.getDeadline().isBefore(limited.getDeadline())) {
      limited = options;
    }
    return next.newCall(method, limited);
  }
}
