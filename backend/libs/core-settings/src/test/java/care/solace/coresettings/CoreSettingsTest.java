package care.solace.coresettings;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.contracts.core.v1.ConfigServiceGrpc;
import care.solace.contracts.core.v1.FeatureFlag;
import care.solace.contracts.core.v1.GetPlatformConfigRequest;
import care.solace.contracts.core.v1.GetPlatformConfigResponse;
import care.solace.contracts.core.v1.PlatformSetting;
import care.solace.grpc.ResilientCalls;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Settings from Core (D61): staff values win, defaults stay for the rest, and Core being away. */
class CoreSettingsTest {

  private final AtomicBoolean down = new AtomicBoolean();
  private Server server;
  private ManagedChannel channel;
  private CoreSettings settings;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new ConfigServiceGrpc.ConfigServiceImplBase() {
                  @Override
                  public void getPlatformConfig(
                      GetPlatformConfigRequest request,
                      StreamObserver<GetPlatformConfigResponse> out) {
                    if (down.get()) {
                      out.onError(Status.UNAVAILABLE.asRuntimeException());
                      return;
                    }
                    out.onNext(
                        GetPlatformConfigResponse.newBuilder()
                            .addSettings(
                                PlatformSetting.newBuilder()
                                    .setKey("solace.tracking.nudges.reminder-window")
                                    .setValue("3")
                                    .setUnit("HOURS"))
                            .addSettings(
                                PlatformSetting.newBuilder()
                                    .setKey("solace.tracking.snooze-days")
                                    .setValue("10")
                                    .setUnit("DAYS"))
                            .addFlags(
                                FeatureFlag.newBuilder()
                                    .setKey("aiTransferAuthorised")
                                    .setEnabled(false))
                            .build());
                    out.onCompleted();
                  }
                })
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    settings =
        new CoreSettings(
            ConfigServiceGrpc.newBlockingStub(channel),
            new ResilientCalls(CircuitBreakerRegistry.ofDefaults()));
  }

  @AfterEach
  void stop() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void staffValuesWinAndTheServiceDefaultsCountForTheRest() {
    assertThat(settings.loaded()).isFalse();
    assertThat(settings.number("solace.tracking.snooze-days", 7)).isEqualTo(7);

    settings.reload();

    assertThat(settings.loaded()).isTrue();
    assertThat(settings.duration("solace.tracking.nudges.reminder-window", Duration.ofHours(2)))
        .isEqualTo(Duration.ofHours(3));
    assertThat(settings.number("solace.tracking.snooze-days", 7)).isEqualTo(10);
    assertThat(settings.number("solace.tracking.trend-window", 14)).isEqualTo(14);
    // Core's flag wins over the service's own default once Core answered.
    assertThat(settings.enabled("aiTransferAuthorised", true)).isFalse();
    assertThat(settings.enabled("unknownFlag", true)).isTrue();
  }

  @Test
  void whileCoreIsAwayTheValuesLastLoadedStayInForce() {
    settings.reload();
    down.set(true);

    settings.reload();

    assertThat(settings.number("solace.tracking.snooze-days", 7)).isEqualTo(10);
  }
}
