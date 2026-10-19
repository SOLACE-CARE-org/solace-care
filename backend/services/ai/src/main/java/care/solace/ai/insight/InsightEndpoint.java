package care.solace.ai.insight;

import care.solace.contracts.ai.v1.ExplainRecommendationRequest;
import care.solace.contracts.ai.v1.ExplainRecommendationResponse;
import care.solace.contracts.ai.v1.InsightServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Tracking asks for a plain wording of a recommendation (insight_service.proto; D56). */
@Service
class InsightEndpoint extends InsightServiceGrpc.InsightServiceImplBase {

  private final Insights insights;

  InsightEndpoint(Insights insights) {
    this.insights = insights;
  }

  @Override
  public void explainRecommendation(
      ExplainRecommendationRequest request,
      StreamObserver<ExplainRecommendationResponse> responses) {
    UUID subject;
    try {
      subject = UUID.fromString(request.getSubjectId());
    } catch (IllegalArgumentException e) {
      responses.onError(Status.INVALID_ARGUMENT.withDescription("subject_id").asRuntimeException());
      return;
    }
    var wording =
        insights.explain(
            subject,
            request.getRuleCode(),
            request.getEvidenceJson(),
            request.getMessageEn(),
            request.getMessageFr());
    var reply = ExplainRecommendationResponse.newBuilder().setWorded(wording.isPresent());
    wording.ifPresent(
        w -> reply.setTextEn(w.en()).setTextFr(w.fr()).setPromptVersion(w.promptVersion()));
    responses.onNext(reply.build());
    responses.onCompleted();
  }
}
