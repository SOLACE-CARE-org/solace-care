package care.solace.core.reviews;

import care.solace.contracts.ai.v1.ModerateReviewRequest;
import care.solace.contracts.ai.v1.ModerationServiceGrpc;
import care.solace.contracts.ai.v1.ModerationServiceGrpc.ModerationServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * The AI service's safety check of a written review (D37, D39). 2-second deadline and a breaker;
 * when AI cannot answer, the review simply waits for a person (AI-4).
 */
interface ReviewModeration {

  /**
   * The safety verdict and, from Phase 3, the model's suggestion for the moderator (D56).
   *
   * @param suggestion PUBLISH or HOLD, null when the feature is off
   */
  record Verdict(boolean risk, String suggestion, java.util.List<String> reasons) {}

  /** The verdict, or empty when AI could not answer. */
  Optional<Verdict> moderate(UUID reviewId, UUID subjectId, String text, String language);

  @Component
  class Grpc implements ReviewModeration {

    private final ModerationServiceBlockingStub stub;
    private final ResilientCalls calls;

    Grpc(ModerationServiceBlockingStub stub, ResilientCalls calls) {
      this.stub = stub;
      this.calls = calls;
    }

    @Override
    public Optional<Verdict> moderate(UUID reviewId, UUID subjectId, String text, String language) {
      return calls.call(
          "ai",
          () -> {
            var answer =
                stub.moderateReview(
                    ModerateReviewRequest.newBuilder()
                        .setReviewId(reviewId.toString())
                        .setSubjectId(subjectId.toString())
                        .setText(text)
                        .setLocale(language)
                        .build());
            String suggestion =
                switch (answer.getSuggestion()) {
                  case DECISION_PUBLISH -> "PUBLISH";
                  case DECISION_HOLD -> "HOLD";
                  default -> null;
                };
            return Optional.of(
                new Verdict(
                    answer.getRisk(),
                    suggestion,
                    answer.getSuggestionReasonsList().stream()
                        .filter(r -> !r.equals("CLEAN"))
                        .toList()));
          },
          failure -> Optional.empty());
    }
  }

  @Configuration(proxyBeanMethods = false)
  class Stubs {
    @Bean
    ModerationServiceBlockingStub aiModeration(GrpcChannelFactory channels) {
      return ModerationServiceGrpc.newBlockingStub(channels.createChannel("ai"));
    }
  }
}
