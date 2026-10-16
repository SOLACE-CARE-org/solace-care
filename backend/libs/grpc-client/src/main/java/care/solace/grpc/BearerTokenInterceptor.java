package care.solace.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.function.Supplier;

/**
 * Sends the calling service's client-credentials token (D2, System Design: narrow scopes such as
 * {@code tracking:summary.read}) as {@code authorization: Bearer ...} on every call. The callee
 * checks the token's scope and audience.
 */
public class BearerTokenInterceptor implements ClientInterceptor {

  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final Supplier<String> token;

  /**
   * @param token returns a current access token; it is asked on every call and is expected to cache
   *     until the token nears expiry
   */
  public BearerTokenInterceptor(Supplier<String> token) {
    this.token = token;
  }

  @Override
  public <Q, R> ClientCall<Q, R> interceptCall(
      MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
      @Override
      public void start(Listener<R> listener, Metadata headers) {
        headers.put(AUTHORIZATION, "Bearer " + token.get());
        super.start(listener, headers);
      }
    };
  }
}
