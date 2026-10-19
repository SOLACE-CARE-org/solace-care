package care.solace.ai.brief;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import care.solace.contracts.core.v1.BriefSourceServiceGrpc.BriefSourceServiceBlockingStub;
import care.solace.contracts.core.v1.GetBriefSourcesRequest;
import care.solace.contracts.core.v1.GetBriefSourcesResponse;
import care.solace.contracts.tracking.v1.GetTrendSummaryRequest;
import care.solace.contracts.tracking.v1.GetTrendSummaryResponse;
import care.solace.contracts.tracking.v1.TrendLine;
import care.solace.contracts.tracking.v1.TrendSummaryServiceGrpc.TrendSummaryServiceBlockingStub;
import care.solace.grpc.ResilientCalls;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * The pre-session brief (System Design, AI feature 3; D56): the booking's facts and safety signals,
 * the health check summary the seeker shared (built by rules in Core: no AI reads those answers,
 * D8), and the consented tracking trend in a sentence or two. A model words only the trend, from
 * numbers; if it is off or refused, the lines are shown as facts. Nothing is stored.
 */
@Service
public class Briefs implements Evaluations.Runner {

  static final int MAX = 500;

  public record Score(String screener, int total, String band) {}

  public record Trend(String summary, boolean aiWorded, List<String> lines) {}

  public record Brief(
      String startsAt,
      String modality,
      String sessionLanguage,
      boolean guest,
      boolean firstSession,
      boolean healthCheckShared,
      String healthCheckSharedAt,
      String healthCheckSummary,
      List<Score> scores,
      boolean healthCheckSafetyStop,
      boolean scribeConsent,
      Trend tracking) {}

  private final BriefSourceServiceBlockingStub core;
  private final TrendSummaryServiceBlockingStub tracking;
  private final ResilientCalls calls;
  private final Features features;
  private final ModelGateway gateway;
  private final MessageSource messages;

  Briefs(
      BriefSourceServiceBlockingStub core,
      TrendSummaryServiceBlockingStub tracking,
      ResilientCalls calls,
      Features features,
      ModelGateway gateway,
      MessageSource messages) {
    this.core = core;
    this.tracking = tracking;
    this.calls = calls;
    this.features = features;
    this.gateway = gateway;
    this.messages = messages;
  }

  /** Empty when the caller is not the booking's specialist, or Core cannot answer. */
  public Optional<Brief> brief(UUID bookingId, UUID specialist, Locale locale) {
    String lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
    GetBriefSourcesResponse sources =
        calls.call(
            "core",
            () ->
                core.getBriefSources(
                    GetBriefSourcesRequest.newBuilder()
                        .setBookingId(bookingId.toString())
                        .setSpecialistSubjectId(specialist.toString())
                        .setLocale(lang)
                        .build()),
            failure -> GetBriefSourcesResponse.getDefaultInstance());
    if (!sources.getAllowed()) {
      return Optional.empty();
    }
    Trend trend = null;
    if (!sources.getGuest() && !sources.getSeekerSubjectId().isBlank()) {
      // Tracking answers only when the seeker shared with this specialist; down, the brief goes on
      // without it (System Design, Synchronous calls).
      GetTrendSummaryResponse summary =
          calls.call(
              "tracking",
              () ->
                  tracking.getTrendSummary(
                      GetTrendSummaryRequest.newBuilder()
                          .setSeekerSubjectId(sources.getSeekerSubjectId())
                          .setBookingId(bookingId.toString())
                          .setSpecialistSubjectId(specialist.toString())
                          .setLocale(lang)
                          .build()),
              failure -> GetTrendSummaryResponse.getDefaultInstance());
      if (summary.getShared() && summary.getLinesCount() > 0) {
        trend =
            trend(UUID.fromString(sources.getSeekerSubjectId()), summary.getLinesList(), locale);
      }
    }
    return Optional.of(
        new Brief(
            sources.getStartsAt(),
            sources.getModality(),
            sources.getSessionLanguage(),
            sources.getGuest(),
            sources.getFirstSession(),
            sources.getHealthCheckShared(),
            sources.getHealthCheckSharedAt().isBlank() ? null : sources.getHealthCheckSharedAt(),
            sources.getHealthCheckShared() ? sources.getHealthCheckSummary() : null,
            sources.getScoresList().stream()
                .map(s -> new Score(s.getScreener(), s.getTotal(), s.getBand()))
                .toList(),
            sources.getHealthCheckSafetyStop(),
            sources.getScribeConsent(),
            trend));
  }

  Trend trend(UUID seeker, List<TrendLine> lines, Locale locale) {
    List<String> facts = lines.stream().map(l -> fact(l, locale)).toList();
    var live = features.live(Feature.PRE_SESSION_BRIEF);
    if (live.isPresent()) {
      var worded = word(live.get(), seeker, facts, locale);
      if (worded.isPresent()) {
        return new Trend(worded.get(), true, facts);
      }
    }
    return new Trend(null, false, facts);
  }

  Optional<String> word(Features.PromptVersion v, UUID seeker, List<String> facts, Locale locale) {
    String joined = String.join("\n", facts);
    var request =
        new ModelGateway.Request(
            Feature.PRE_SESSION_BRIEF,
            seeker,
            v.version(),
            v.systemPrompt(),
            "Language: "
                + ("fr".equals(locale.getLanguage()) ? "French" : "English")
                + "\n"
                + joined,
            true);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    String text = answer.get().text();
    var refused =
        Guardrails.length(text, MAX)
            .or(() -> Guardrails.clinical(text))
            .or(() -> Guardrails.numbersFrom(text, joined));
    if (refused.isPresent()) {
      gateway.refused(request, answer.get().route(), text);
      return Optional.empty();
    }
    return Optional.of(text);
  }

  /** "Mood: 9 of 14 days logged, average 2.6" — the facts a specialist reads, by rules. */
  String fact(TrendLine l, Locale locale) {
    String key =
        switch (l.getUnit()) {
          case "MINUTES" -> "brief.trend.minutes";
          case "SCALE" -> "brief.trend.average";
          case "YES_NO" -> "brief.trend.yes";
          default -> "brief.trend.most-often";
        };
    Object value =
        switch (l.getUnit()) {
          case "MINUTES" -> Math.round(l.getAverage());
          case "SCALE" -> l.getAverage();
          default -> l.getMostOften();
        };
    return messages.getMessage(
        key,
        new Object[] {l.getElementName(), l.getDaysLogged(), l.getWindowDays(), value},
        locale);
  }

  // --- Evaluation -------------------------------------------------------------------------------

  @Override
  public Feature feature() {
    return Feature.PRE_SESSION_BRIEF;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    List<String> facts = new ArrayList<>();
    input.path("facts").forEach(f -> facts.add(f.asText()));
    var text = word(version, null, facts, locale);
    if (text.isEmpty()) {
      return Optional.of("no-wording");
    }
    for (JsonNode must : expect.path("mentions")) {
      if (!text.get().toLowerCase(Locale.ROOT).contains(must.asText().toLowerCase(Locale.ROOT))) {
        return Optional.of("missing:" + must.asText());
      }
    }
    return Optional.empty();
  }
}
