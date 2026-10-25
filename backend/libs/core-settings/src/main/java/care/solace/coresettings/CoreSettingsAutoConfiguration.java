package care.solace.coresettings;

import care.solace.contracts.core.v1.ConfigServiceGrpc;
import care.solace.grpc.ResilientCalls;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * {@link CoreSettings} for a service that calls Core over gRPC, on the {@code core} channel with
 * the usual deadline and circuit breaker (D61).
 */
@AutoConfiguration
public class CoreSettingsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  CoreSettings coreSettings(GrpcChannelFactory channels, ResilientCalls calls) {
    return new CoreSettings(
        ConfigServiceGrpc.newBlockingStub(channels.createChannel("core")), calls);
  }

  /** Loads once the service is up; until then, and while Core is away, the defaults count. */
  @Bean
  ApplicationRunner coreSettingsFirstLoad(CoreSettings settings) {
    return args -> settings.reload();
  }
}
