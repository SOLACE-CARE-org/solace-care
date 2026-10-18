package care.solace.ai.grpc;

import care.solace.ai.safety.SafetyChecks;
import care.solace.contracts.ai.v1.GetRiskDecisionRequest;
import care.solace.contracts.ai.v1.GetRiskDecisionResponse;
import care.solace.contracts.ai.v1.RiskCategory;
import care.solace.contracts.ai.v1.RiskServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The category behind an ai.risk.flagged event, for Core's crisis queue (D39). */
@Service
class RiskEndpoint extends RiskServiceGrpc.RiskServiceImplBase {

  private final SafetyChecks safety;

  RiskEndpoint(SafetyChecks safety) {
    this.safety = safety;
  }

  @Override
  public void getRiskDecision(
      GetRiskDecisionRequest request, StreamObserver<GetRiskDecisionResponse> responses) {
    UUID id;
    try {
      id = UUID.fromString(request.getDecisionId());
    } catch (IllegalArgumentException e) {
      responses.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    var found = safety.decision(id);
    if (found.isEmpty() || found.get().category() == null) {
      responses.onError(Status.NOT_FOUND.asRuntimeException());
      return;
    }
    var d = found.get();
    responses.onNext(
        GetRiskDecisionResponse.newBuilder()
            .setDecisionId(d.id().toString())
            .setCategory(RiskCategory.valueOf("RISK_CATEGORY_" + d.category()))
            .setSource(d.source())
            .setSourceId(d.sourceId().toString())
            .setSubjectId(d.subjectId().toString())
            .setClassifierVersion(d.classifierVersion())
            .setPolicyVersion(d.policyVersion())
            .build());
    responses.onCompleted();
  }
}
