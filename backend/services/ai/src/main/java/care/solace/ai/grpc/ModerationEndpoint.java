package care.solace.ai.grpc;

import care.solace.ai.safety.SafetyChecks;
import care.solace.contracts.ai.v1.Decision;
import care.solace.contracts.ai.v1.ModerateReviewRequest;
import care.solace.contracts.ai.v1.ModerateReviewResponse;
import care.solace.contracts.ai.v1.ModerationServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Review moderation for Core. Phase 1 holds every review for a person (human moderation until Phase
 * 3); the safety monitor's flag is what this adds (D37, D39). The text is never stored or logged.
 */
@Service
class ModerationEndpoint extends ModerationServiceGrpc.ModerationServiceImplBase {

  private final SafetyChecks safety;

  ModerationEndpoint(SafetyChecks safety) {
    this.safety = safety;
  }

  @Override
  public void moderateReview(
      ModerateReviewRequest request, StreamObserver<ModerateReviewResponse> responses) {
    UUID reviewId;
    UUID subjectId;
    try {
      reviewId = UUID.fromString(request.getReviewId());
      subjectId = UUID.fromString(request.getSubjectId());
    } catch (IllegalArgumentException e) {
      responses.onError(Status.INVALID_ARGUMENT.withDescription("ids").asRuntimeException());
      return;
    }
    var decision =
        safety.check(
            "REVIEW",
            reviewId,
            subjectId,
            request.getText(),
            Locale.forLanguageTag(request.getLocale().isBlank() ? "en" : request.getLocale()));
    responses.onNext(
        ModerateReviewResponse.newBuilder()
            .setDecision(Decision.DECISION_HOLD)
            .setRisk(decision.flagged())
            .setDecisionId(decision.id().toString())
            .build());
    responses.onCompleted();
  }
}
