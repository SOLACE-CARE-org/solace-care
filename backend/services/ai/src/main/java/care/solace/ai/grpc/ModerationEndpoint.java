package care.solace.ai.grpc;

import care.solace.ai.moderation.ReviewModeration;
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
 * Review moderation for Core. Every review still waits for a person (D37); the safety monitor's
 * flag, and from Phase 3 a model's suggestion for the moderator, are what this adds (D39, D56). The
 * text is never logged; the gateway keeps it [30] days for the safety audit.
 */
@Service
class ModerationEndpoint extends ModerationServiceGrpc.ModerationServiceImplBase {

  private final SafetyChecks safety;
  private final ReviewModeration moderation;

  ModerationEndpoint(SafetyChecks safety, ReviewModeration moderation) {
    this.safety = safety;
    this.moderation = moderation;
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
    var reply =
        ModerateReviewResponse.newBuilder()
            .setDecision(Decision.DECISION_HOLD)
            .setRisk(decision.flagged())
            .setDecisionId(decision.id().toString());
    // Phase 3: a suggestion for the moderator, never for a review the safety monitor flagged.
    if (!decision.flagged()) {
      moderation
          .suggest(subjectId, request.getText())
          .ifPresent(
              s ->
                  reply
                      .setSuggestion(
                          s.decision().equals("PUBLISH")
                              ? Decision.DECISION_PUBLISH
                              : Decision.DECISION_HOLD)
                      .addAllSuggestionReasons(
                          s.reasons().isEmpty() ? java.util.List.of("CLEAN") : s.reasons()));
    }
    responses.onNext(reply.build());
    responses.onCompleted();
  }
}
