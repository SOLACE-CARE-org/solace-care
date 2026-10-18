package care.solace.core.crisis;

import care.solace.contracts.ai.v1.GetRiskDecisionRequest;
import care.solace.contracts.ai.v1.RiskServiceGrpc;
import care.solace.contracts.ai.v1.RiskServiceGrpc.RiskServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * The category behind an ai.risk.flagged event, read from the AI service (D39). 2-second deadline;
 * when AI cannot answer, the alert is raised anyway with the category unknown: on-call must hear.
 */
interface RiskDecisions {

  /** One of the six category names, or empty. */
  Optional<String> category(UUID decisionId);

  @Component
  class Grpc implements RiskDecisions {

    private final RiskServiceBlockingStub stub;
    private final ResilientCalls calls;

    Grpc(RiskServiceBlockingStub stub, ResilientCalls calls) {
      this.stub = stub;
      this.calls = calls;
    }

    @Override
    public Optional<String> category(UUID decisionId) {
      return calls.call(
          "ai",
          () ->
              Optional.of(
                  stub.getRiskDecision(
                          GetRiskDecisionRequest.newBuilder()
                              .setDecisionId(decisionId.toString())
                              .build())
                      .getCategory()
                      .name()
                      .replace("RISK_CATEGORY_", "")),
          failure -> Optional.empty());
    }
  }

  @Configuration(proxyBeanMethods = false)
  class Stubs {
    @Bean
    RiskServiceBlockingStub aiRisk(GrpcChannelFactory channels) {
      return RiskServiceGrpc.newBlockingStub(channels.createChannel("ai"));
    }
  }
}
