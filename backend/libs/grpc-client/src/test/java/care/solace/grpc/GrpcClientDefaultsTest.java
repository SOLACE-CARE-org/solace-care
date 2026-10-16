package care.solace.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.contracts.platform.v1.PingRequest;
import care.solace.contracts.platform.v1.PingResponse;
import care.solace.contracts.platform.v1.PingServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcClientDefaultsTest {

  /** What the fake callee does on the next calls. */
  enum Mode {
    ANSWER,
    SLOW,
    UNAVAILABLE,
    NOT_FOUND
  }

  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ANSWER);
  private final AtomicInteger served = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();

  private Server server;
  private ManagedChannel channel;
  private PingServiceGrpc.PingServiceBlockingStub ping;
  private ResilientCalls calls;
  private CircuitBreakerRegistry breakers;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    ServerInterceptor captureAuth =
        new ServerInterceptor() {
          @Override
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            authorization.set(headers.get(BearerTokenInterceptor.AUTHORIZATION));
            return next.startCall(call, headers);
          }
        };
    server =
        InProcessServerBuilder.forName(name)
            .addService(ServerInterceptors.intercept(new FakeCallee(), captureAuth))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).build();
    ping =
        PingServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel,
                new DeadlineInterceptor(),
                new BearerTokenInterceptor(() -> "service-token")));
    breakers =
        CircuitBreakerRegistry.of(
            ResilientCalls.breakerConfig(new BreakerSettings(4, 4, 50f, Duration.ofMinutes(1), 1)));
    calls = new ResilientCalls(breakers);
  }

  @AfterEach
  void stop() throws Exception {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void answersPassThroughWithTheServiceToken() {
    String service =
        calls.call("tracking", () -> ping.ping(request()).getService(), e -> "fallback");

    assertThat(service).isEqualTo("tracking");
    assertThat(authorization.get()).isEqualTo("Bearer service-token");
  }

  @Test
  void slowCalleeIsCutOffAtTwoSecondsAndTheFallbackAnswers() {
    mode.set(Mode.SLOW);
    long start = System.nanoTime();

    String service =
        calls.call("tracking", () -> ping.ping(request()).getService(), e -> "fallback");

    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(service).isEqualTo("fallback");
    assertThat(took).isBetween(Duration.ofMillis(1900), Duration.ofMillis(3500));
  }

  @Test
  void shorterDeadlineSetByTheCallerIsKept() {
    mode.set(Mode.SLOW);
    long start = System.nanoTime();

    String service =
        calls.call(
            "tracking",
            () -> ping.withDeadlineAfter(300, TimeUnit.MILLISECONDS).ping(request()).getService(),
            e -> "fallback");

    assertThat(service).isEqualTo("fallback");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  void unwellCalleeOpensTheBreakerAndCallsStopReachingIt() {
    mode.set(Mode.UNAVAILABLE);
    for (int i = 0; i < 4; i++) {
      calls.call("core", () -> ping.ping(request()).getService(), e -> "fallback");
    }
    assertThat(breakers.circuitBreaker("core").getState()).isEqualTo(CircuitBreaker.State.OPEN);

    int servedBefore = served.get();
    String service = calls.call("core", () -> ping.ping(request()).getService(), e -> "fallback");

    assertThat(service).isEqualTo("fallback");
    assertThat(served.get()).isEqualTo(servedBefore);
  }

  @Test
  void businessAnswersUseTheFallbackButDoNotOpenTheBreaker() {
    mode.set(Mode.NOT_FOUND);
    for (int i = 0; i < 6; i++) {
      assertThat(calls.call("ai", () -> ping.ping(request()).getService(), e -> "fallback"))
          .isEqualTo("fallback");
    }

    assertThat(breakers.circuitBreaker("ai").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void breakersAreSeparatePerCallee() {
    mode.set(Mode.UNAVAILABLE);
    for (int i = 0; i < 4; i++) {
      calls.call("core", () -> ping.ping(request()).getService(), e -> "fallback");
    }
    mode.set(Mode.ANSWER);

    assertThat(calls.call("ai", () -> ping.ping(request()).getService(), e -> "fallback"))
        .isEqualTo("tracking");
  }

  private static PingRequest request() {
    return PingRequest.getDefaultInstance();
  }

  private final class FakeCallee extends PingServiceGrpc.PingServiceImplBase {
    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responses) {
      served.incrementAndGet();
      switch (mode.get()) {
        case ANSWER -> {
          responses.onNext(
              PingResponse.newBuilder().setService("tracking").setVersion("1").build());
          responses.onCompleted();
        }
        case SLOW -> {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          responses.onNext(PingResponse.getDefaultInstance());
          responses.onCompleted();
        }
        case UNAVAILABLE -> responses.onError(Status.UNAVAILABLE.asRuntimeException());
        case NOT_FOUND -> responses.onError(Status.NOT_FOUND.asRuntimeException());
      }
    }
  }
}
