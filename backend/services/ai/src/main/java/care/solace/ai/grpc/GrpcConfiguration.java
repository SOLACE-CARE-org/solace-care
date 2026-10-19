package care.solace.ai.grpc;

import care.solace.security.GrpcMethodScopes;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scope each gRPC method of AI requires (infra/keycloak/realms/solace-staff-realm.json). A
 * method missing here is refused, even for a valid token.
 */
@Configuration(proxyBeanMethods = false)
class GrpcConfiguration {

  @Bean
  GrpcMethodScopes grpcMethodScopes() {
    return new GrpcMethodScopes(
        Map.of(
            "solace.platform.v1.PingService/Ping", "ai:ping",
            "solace.ai.v1.ModerationService/ModerateReview", "ai:moderation.invoke",
            "solace.ai.v1.RiskService/GetRiskDecision", "ai:risk.read",
            "solace.ai.v1.InsightService/ExplainRecommendation", "ai:insight.invoke",
            "solace.ai.v1.TranslationService/DraftTranslation", "ai:translation.invoke"));
  }
}
