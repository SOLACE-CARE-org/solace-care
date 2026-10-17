package care.solace.tracking.grpc;

import care.solace.security.GrpcMethodScopes;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scope each gRPC method of Tracking requires (infra/keycloak/realms/solace-staff-realm.json).
 * A method missing here is refused, even for a valid token.
 */
@Configuration(proxyBeanMethods = false)
class GrpcConfiguration {

  @Bean
  GrpcMethodScopes grpcMethodScopes() {
    return new GrpcMethodScopes(
        Map.of(
            "solace.platform.v1.PingService/Ping", "tracking:ping",
            "solace.tracking.v1.TrendSummaryService/GetTrendSummary", "tracking:summary.read"));
  }
}
