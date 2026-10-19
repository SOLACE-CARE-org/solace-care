package care.solace.ai.copilot;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import care.solace.contracts.core.v1.Metric;
import care.solace.contracts.core.v1.MetricsServiceGrpc;
import care.solace.contracts.core.v1.Period;
import care.solace.contracts.core.v1.QueryMetricRequest;
import care.solace.contracts.core.v1.QueryMetricResponse;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.grpc.ResilientCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Service;

/**
 * The admin copilot (admin screen 27; System Design, AI feature 8; D58). A question in words is
 * mapped by a model to one metric of Core's fixed catalogue; Core answers with aggregated,
 * de-identified counts; the answer is worded only from that table, or by a plain sentence when no
 * model may. It reads, never changes, and never looks up a person.
 */
@Service
class Copilot implements Evaluations.Runner {

  static final List<String> METRICS =
      List.of(
          "BOOKINGS_BY_SOURCE",
          "INVITES_BY_MODULE",
          "SESSIONS_BY_LANGUAGE",
          "SESSIONS_BY_MODALITY",
          "PAYMENTS_BY_STATUS",
          "RATINGS",
          "BOOKINGS_BY_STATUS");
  static final List<String> PERIODS =
      List.of("LAST_7_DAYS", "LAST_30_DAYS", "LAST_MONTH", "THIS_QUARTER");

  record Choice(String metric, String period) {}

  record Answer(
      String answer,
      boolean aiWorded,
      String metric,
      String period,
      List<String> columns,
      List<List<String>> rows,
      String source,
      String from,
      String to,
      boolean suppressed) {}

  private final MetricsServiceGrpc.MetricsServiceBlockingStub core;
  private final ResilientCalls calls;
  private final Features features;
  private final ModelGateway gateway;
  private final ObjectMapper json;
  private final MessageSource messages;

  Copilot(
      MetricsServiceGrpc.MetricsServiceBlockingStub core,
      ResilientCalls calls,
      Features features,
      ModelGateway gateway,
      ObjectMapper json,
      MessageSource messages) {
    this.core = core;
    this.calls = calls;
    this.features = features;
    this.gateway = gateway;
    this.json = json;
    this.messages = messages;
  }

  Answer ask(String question, String metric, String period, Locale locale) {
    String lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
    var live = features.live(Feature.ADMIN_COPILOT);
    Choice choice;
    if (metric != null) {
      if (!METRICS.contains(metric) || (period != null && !PERIODS.contains(period))) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      choice = new Choice(metric, period == null ? "LAST_30_DAYS" : period);
    } else {
      if (question == null || question.isBlank() || question.length() > 300) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
      choice =
          live.flatMap(v -> choose(v, question))
              .orElseThrow(() -> new SolaceException(CopilotError.NO_MATCH));
    }
    QueryMetricResponse table =
        calls.call(
            "core",
            () ->
                core.queryMetric(
                    QueryMetricRequest.newBuilder()
                        .setMetric(Metric.valueOf("METRIC_" + choice.metric()))
                        .setPeriod(Period.valueOf("PERIOD_" + choice.period()))
                        .setLocale(lang)
                        .build()),
            failure -> null);
    if (table == null) {
      throw new SolaceException(CommonError.DEPENDENCY_UNAVAILABLE);
    }
    List<List<String>> rows =
        table.getRowsList().stream().map(r -> List.copyOf(r.getCellsList())).toList();
    Optional<String> worded =
        question == null || live.isEmpty()
            ? Optional.empty()
            : word(live.get(), question, table, lang);
    String answer =
        worded.orElseGet(
            () ->
                messages.getMessage(
                    rows.isEmpty() ? "copilot.empty" : "copilot.answer",
                    new Object[] {
                      messages.getMessage("copilot.period." + choice.period(), null, locale)
                    },
                    locale));
    return new Answer(
        answer,
        worded.isPresent(),
        choice.metric(),
        choice.period(),
        List.copyOf(table.getColumnsList()),
        rows,
        table.getSource(),
        table.getFrom(),
        table.getTo(),
        table.getSuppressed());
  }

  /** The model picks a metric and a period from the catalogue; anything else is no match. */
  Optional<Choice> choose(Features.PromptVersion v, String question) {
    var request =
        new ModelGateway.Request(
            Feature.ADMIN_COPILOT,
            null,
            v.version(),
            v.systemPrompt(),
            "Task: choose\nMetrics: "
                + String.join(", ", METRICS)
                + "\nPeriods: "
                + String.join(", ", PERIODS)
                + "\nAnswer with JSON only: {\"metric\": \"...\", \"period\": \"...\"}"
                + "\n\nQuestion: "
                + question,
            false);
    return gateway
        .complete(request)
        .flatMap(
            a -> {
              try {
                JsonNode n = json.readTree(strip(a.text()));
                String m = n.path("metric").asText();
                String p = n.path("period").asText("LAST_30_DAYS");
                return METRICS.contains(m) && PERIODS.contains(p)
                    ? Optional.of(new Choice(m, p))
                    : Optional.empty();
              } catch (Exception e) {
                return Optional.empty();
              }
            });
  }

  private Optional<String> word(
      Features.PromptVersion v, String question, QueryMetricResponse table, String lang) {
    String rendered =
        String.join(" | ", table.getColumnsList())
            + "\n"
            + table.getRowsList().stream()
                .map(r -> String.join(" | ", r.getCellsList()))
                .collect(Collectors.joining("\n"));
    var request =
        new ModelGateway.Request(
            Feature.ADMIN_COPILOT,
            null,
            v.version(),
            v.systemPrompt(),
            "Task: answer\nWrite in: "
                + ("fr".equals(lang) ? "French" : "English")
                + "\nPeriod: "
                + table.getFrom()
                + " to "
                + table.getTo()
                + "\n\nTable:\n"
                + rendered
                + "\n\nQuestion: "
                + question,
            false);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    String text = answer.get().text().strip();
    // Numbers only from the table (sums of its counts are allowed), no clinical words, short.
    String evidence = rendered + " " + sums(table);
    if (Guardrails.clinical(text).isPresent()
        || Guardrails.numbersFrom(text, evidence).isPresent()
        || Guardrails.length(text, 400).isPresent()) {
      gateway.refused(request, answer.get().route(), text);
      return Optional.empty();
    }
    return Optional.of(text);
  }

  /** Column totals, so "112 bookings in all" passes when the rows add up to 112. */
  private static String sums(QueryMetricResponse table) {
    StringBuilder out = new StringBuilder();
    for (int c = 1; c < table.getColumnsCount(); c++) {
      long sum = 0;
      boolean numeric = true;
      for (var r : table.getRowsList()) {
        try {
          sum += Long.parseLong(r.getCells(c));
        } catch (RuntimeException e) {
          numeric = false;
        }
      }
      if (numeric) {
        out.append(sum).append(' ');
      }
    }
    return out.toString();
  }

  /** Made-up staff questions (eval/admin-copilot.json): the right metric is chosen. */
  @Override
  public Feature feature() {
    return Feature.ADMIN_COPILOT;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    var choice = choose(version, input.path("question").asText());
    if (choice.isEmpty()) {
      return Optional.of("no-metric");
    }
    return choice.get().metric().equals(expect.path("metric").asText())
        ? Optional.empty()
        : Optional.of("wrong-metric:" + choice.get().metric());
  }

  private static String strip(String text) {
    String t = text.strip();
    if (t.startsWith("```")) {
      t = t.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").strip();
    }
    return t;
  }

  @Configuration(proxyBeanMethods = false)
  static class Stubs {
    @Bean
    MetricsServiceGrpc.MetricsServiceBlockingStub coreMetrics(GrpcChannelFactory channels) {
      return MetricsServiceGrpc.newBlockingStub(channels.createChannel("core"));
    }
  }
}
