package care.solace.ai.translation;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import care.solace.contracts.ai.v1.DraftTranslationRequest;
import care.solace.contracts.ai.v1.DraftTranslationResponse;
import care.solace.contracts.ai.v1.TranslationServiceGrpc;
import com.fasterxml.jackson.databind.JsonNode;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * EN↔FR drafts of articles, bios and notifications (System Design, AI feature 6; D56). A person
 * reviews and approves every draft before anything is published; a draft that loses or invents a
 * number, or grows past three times its source, is thrown away.
 */
@Service
class Translations extends TranslationServiceGrpc.TranslationServiceImplBase
    implements Evaluations.Runner {

  private static final Set<String> KINDS = Set.of("ARTICLE", "BIO", "NOTIFICATION");
  private static final Set<String> LOCALES = Set.of("en", "fr");

  private final Features features;
  private final ModelGateway gateway;

  Translations(Features features, ModelGateway gateway) {
    this.features = features;
    this.gateway = gateway;
  }

  @Override
  public void draftTranslation(
      DraftTranslationRequest request, StreamObserver<DraftTranslationResponse> responses) {
    if (!KINDS.contains(request.getKind())
        || !LOCALES.contains(request.getSourceLocale())
        || !LOCALES.contains(request.getTargetLocale())
        || request.getSourceLocale().equals(request.getTargetLocale())
        || request.getText().isBlank()
        || request.getText().length() > 20_000) {
      responses.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    var live = features.live(Feature.TRANSLATION);
    var reply = DraftTranslationResponse.newBuilder().setDrafted(false);
    if (live.isPresent()) {
      draft(
              live.get(),
              request.getKind(),
              request.getSourceLocale(),
              request.getTargetLocale(),
              request.getText())
          .ifPresent(
              text -> reply.setDrafted(true).setText(text).setPromptVersion(live.get().version()));
    }
    responses.onNext(reply.build());
    responses.onCompleted();
  }

  Optional<String> draft(
      Features.PromptVersion v, String kind, String from, String to, String text) {
    var request =
        new ModelGateway.Request(
            Feature.TRANSLATION,
            null,
            v.version(),
            v.systemPrompt(),
            "Kind: " + kind + "\nFrom: " + name(from) + "\nTo: " + name(to) + "\n\n" + text,
            false);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    String out = answer.get().text();
    var refused =
        Guardrails.length(out, Math.max(200, text.length() * 3))
            .or(() -> Guardrails.numbersFrom(out, text))
            .or(() -> Guardrails.numbersFrom(text, out));
    if (refused.isPresent()) {
      gateway.refused(request, answer.get().route(), out);
      return Optional.empty();
    }
    return Optional.of(out);
  }

  private static String name(String locale) {
    return locale.equals("fr") ? "French" : "English";
  }

  @Override
  public Feature feature() {
    return Feature.TRANSLATION;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    var out =
        draft(
            version,
            input.path("kind").asText("ARTICLE"),
            input.path("from").asText(),
            locale.getLanguage(),
            input.path("text").asText());
    if (out.isEmpty()) {
      return Optional.of("no-draft");
    }
    for (JsonNode must : expect.path("mentions")) {
      if (!out.get().toLowerCase(Locale.ROOT).contains(must.asText().toLowerCase(Locale.ROOT))) {
        return Optional.of("missing:" + must.asText());
      }
    }
    return Optional.empty();
  }
}
