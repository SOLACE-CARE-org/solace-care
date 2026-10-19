package care.solace.core.assist;

import care.solace.contracts.ai.v1.DraftTranslationRequest;
import care.solace.contracts.ai.v1.TranslationServiceGrpc;
import care.solace.contracts.ai.v1.TranslationServiceGrpc.TranslationServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * An EN↔FR draft from the AI service (translation_service.proto; D56). Empty when the feature is
 * off, the draft failed a guardrail, or AI did not answer in 2 seconds: the person writes it.
 */
public interface TranslationDrafts {

  /**
   * @param kind ARTICLE, BIO or NOTIFICATION
   * @param from "en" or "fr"
   * @param requestedBy a staff id or a specialist's account id, for budgets
   */
  Optional<String> draft(String kind, String from, String to, String text, String requestedBy);

  @Component
  class Grpc implements TranslationDrafts {

    private final TranslationServiceBlockingStub stub;
    private final ResilientCalls calls;

    Grpc(TranslationServiceBlockingStub stub, ResilientCalls calls) {
      this.stub = stub;
      this.calls = calls;
    }

    @Override
    public Optional<String> draft(
        String kind, String from, String to, String text, String requestedBy) {
      if (text == null || text.isBlank()) {
        return Optional.empty();
      }
      return calls.call(
          "ai",
          () -> {
            var answer =
                stub.draftTranslation(
                    DraftTranslationRequest.newBuilder()
                        .setKind(kind)
                        .setSourceLocale(from)
                        .setTargetLocale(to)
                        .setText(text)
                        .setRequestedBy(requestedBy)
                        .build());
            return answer.getDrafted() ? Optional.of(answer.getText()) : Optional.<String>empty();
          },
          failure -> Optional.empty());
    }
  }

  @Configuration(proxyBeanMethods = false)
  class Stubs {
    @Bean
    TranslationServiceBlockingStub aiTranslation(GrpcChannelFactory channels) {
      return TranslationServiceGrpc.newBlockingStub(channels.createChannel("ai"));
    }
  }
}
