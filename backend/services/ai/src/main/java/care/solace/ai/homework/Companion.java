package care.solace.ai.homework;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.Guardrails;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.privacy.Sealer;
import care.solace.ai.safety.SafetyChecks;
import care.solace.ai.scribe.ScribeError;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The homework companion (Companion.dc.html; System Design, AI feature 5; D57). It follows the
 * specialist's plan step by step in words written for it (EN/FR), checks every answer with the
 * safety monitor before replying, and on a risk signal stops and hands off to urgent help. A model
 * may only word the closing reflection, from the seeker's own record and the vetted corpus; it
 * never diagnoses, and without a model the companion still works.
 */
@Service
class Companion implements Evaluations.Runner {

  static final Map<String, List<String>> STEPS =
      Map.of(
          "THOUGHT_RECORD", List.of("SITUATION", "THOUGHT", "FEELING", "KINDER_VIEW"),
          "SLEEP_DIARY", List.of("BEDTIME", "WAKE_TIME", "RESTED"),
          "WORRY_TIME", List.of("WORRIES"),
          "CUSTOM", List.of("TASK"));

  private static final int REFLECTION_MAX = 400;

  record Turn(
      String step,
      String prompt,
      boolean aiWorded,
      Map<String, String> record,
      int done,
      int target,
      boolean finished,
      boolean crisis) {}

  private record Entry(UUID id, Map<String, String> record) {}

  private final JdbcClient db;
  private final Homeworks homeworks;
  private final SafetyChecks safety;
  private final Sealer sealer;
  private final ObjectMapper json;
  private final MessageSource messages;
  private final Features features;
  private final ModelGateway gateway;
  private final Corpus corpus;
  private final Clock clock;

  Companion(
      JdbcClient db,
      Homeworks homeworks,
      SafetyChecks safety,
      Sealer sealer,
      ObjectMapper json,
      MessageSource messages,
      Features features,
      ModelGateway gateway,
      Corpus corpus,
      Clock clock) {
    this.db = db;
    this.homeworks = homeworks;
    this.safety = safety;
    this.sealer = sealer;
    this.json = json;
    this.messages = messages;
    this.features = features;
    this.gateway = gateway;
    this.corpus = corpus;
    this.clock = clock;
  }

  Turn state(UUID homeworkId, UUID seeker, Locale locale) {
    var h = owned(homeworkId, seeker);
    Optional<Entry> open = open(homeworkId);
    return next(h, open.map(Entry::record).orElse(Map.of()), locale, null, false);
  }

  @Transactional
  Turn answer(UUID homeworkId, UUID seeker, String answer, Locale locale) {
    var h = owned(homeworkId, seeker);
    String text = answer == null ? "" : answer.strip();
    if (text.isEmpty() || text.length() > 2000) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (h.done() >= h.target() || !h.dueBy().isAfter(clock.instant())) {
      throw new SolaceException(ScribeError.HOMEWORK_CLOSED);
    }
    // Every answer passes the safety monitor first; a flag reaches on-call (ai.risk.flagged).
    if (safety.check("COMPANION", homeworkId, seeker, text, locale).flagged()) {
      return new Turn(
          "DONE",
          message("companion.crisis", locale),
          false,
          open(homeworkId).map(Entry::record).orElse(Map.of()),
          h.done(),
          h.target(),
          false,
          true);
    }
    List<String> steps = STEPS.get(h.exercise());
    Optional<Entry> open = open(homeworkId);
    Map<String, String> record = new LinkedHashMap<>(open.map(Entry::record).orElse(Map.of()));
    String step = steps.stream().filter(s -> !record.containsKey(s)).findFirst().orElseThrow();
    record.put(step, text);
    boolean complete = record.keySet().containsAll(steps);
    Instant now = clock.instant();
    if (open.isPresent()) {
      db.sql(
              "UPDATE homework_entry SET sealed = :s, step = :st, completed = :c,"
                  + " completed_at = :at WHERE id = :id")
          .param("id", open.get().id())
          .param("s", seal(record))
          .param("st", step)
          .param("c", complete)
          .param("at", complete ? Timestamp.from(now) : null)
          .update();
    } else {
      db.sql(
              """
              INSERT INTO homework_entry (id, homework_id, sealed, step, completed, created_at,
                                          completed_at)
              VALUES (:id, :h, :s, :st, :c, :now, :at)
              """)
          .param("id", UUID.randomUUID())
          .param("h", homeworkId)
          .param("s", seal(record))
          .param("st", step)
          .param("c", complete)
          .param("now", Timestamp.from(now))
          .param("at", complete ? Timestamp.from(now) : null)
          .update();
    }
    var after = owned(homeworkId, seeker);
    if (!complete) {
      return next(after, record, locale, null, false);
    }
    // The record is done: a short reflection, worded by a model from vetted content when it may.
    Optional<String> reflection =
        after.companionGuided() ? reflect(h.exercise(), record, seeker, locale) : Optional.empty();
    String closing =
        after.done() >= after.target()
            ? message("companion.finished", locale)
            : message("companion.saved", locale);
    String prompt = reflection.map(r -> r + " " + closing).orElse(closing);
    return new Turn(
        "DONE",
        prompt,
        reflection.isPresent(),
        record,
        after.done(),
        after.target(),
        after.done() >= after.target(),
        false);
  }

  /** The next question: the start of a new record, or the next step of the open one. */
  private Turn next(
      Homeworks.Homework h,
      Map<String, String> record,
      Locale locale,
      String lead,
      boolean aiWorded) {
    boolean finished = h.done() >= h.target();
    if (finished) {
      return new Turn(
          "DONE",
          message("companion.finished", locale),
          false,
          record,
          h.done(),
          h.target(),
          true,
          false);
    }
    List<String> steps = STEPS.get(h.exercise());
    String step = steps.stream().filter(s -> !record.containsKey(s)).findFirst().orElseThrow();
    String prompt;
    if (record.isEmpty()) {
      prompt =
          switch (h.exercise()) {
            case "THOUGHT_RECORD" ->
                message(
                    "companion.start.THOUGHT_RECORD",
                    locale,
                    message("companion.ordinal." + Math.min(h.done() + 1, 10), locale));
            case "CUSTOM" -> message("companion.start.CUSTOM", locale, h.customTask());
            default -> message("companion.start." + h.exercise(), locale);
          };
    } else {
      prompt = message("companion.step." + step, locale);
    }
    return new Turn(step, prompt, aiWorded, record, h.done(), h.target(), false, false);
  }

  private Optional<String> reflect(
      String exercise, Map<String, String> record, UUID seeker, Locale locale) {
    var live = features.live(Feature.COMPANION);
    if (live.isEmpty()) {
      return Optional.empty();
    }
    return reflection(live.get(), exercise, record, seeker, locale);
  }

  Optional<String> reflection(
      Features.PromptVersion v,
      String exercise,
      Map<String, String> record,
      UUID seeker,
      Locale locale) {
    String lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
    String answers =
        String.join(
            "\n", record.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
    List<String> passages = corpus.passages(exercise, lang, answers, 3);
    String evidence = answers + "\n" + String.join("\n", passages);
    var request =
        new ModelGateway.Request(
            Feature.COMPANION,
            seeker,
            v.version(),
            v.systemPrompt(),
            "Write in: "
                + ("fr".equals(lang) ? "French" : "English")
                + "\nExercise: "
                + exercise
                + "\n\nVetted content:\n"
                + String.join("\n", passages)
                + "\n\nThe person's record:\n"
                + answers,
            true);
    var answer = gateway.complete(request);
    if (answer.isEmpty()) {
      return Optional.empty();
    }
    String text = answer.get().text().strip();
    if (Guardrails.clinical(text).isPresent()
        || Guardrails.numbersFrom(text, evidence).isPresent()
        || Guardrails.length(text, REFLECTION_MAX).isPresent()) {
      gateway.refused(request, answer.get().route(), text);
      return Optional.empty();
    }
    return Optional.of(text);
  }

  /** Made-up records (eval/companion.json): a reflection comes back and passes the guardrails. */
  @Override
  public Feature feature() {
    return Feature.COMPANION;
  }

  @Override
  public Optional<String> run(
      Features.PromptVersion version, JsonNode input, JsonNode expect, Locale locale) {
    Map<String, String> record = new LinkedHashMap<>();
    input.path("record").properties().forEach(e -> record.put(e.getKey(), e.getValue().asText()));
    return reflection(version, input.path("exercise").asText(), record, null, locale).isPresent()
        ? Optional.empty()
        : Optional.of("no-reflection-or-guardrail");
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private Homeworks.Homework owned(UUID id, UUID seeker) {
    return homeworks
        .find(id)
        .filter(o -> o.seekerSubjectId().equals(seeker))
        .map(Homeworks.Owned::homework)
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private Optional<Entry> open(UUID homeworkId) {
    return db.sql(
            "SELECT id, sealed FROM homework_entry WHERE homework_id = :h AND NOT completed"
                + " ORDER BY created_at DESC LIMIT 1")
        .param("h", homeworkId)
        .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), open(rs.getBytes(2))))
        .optional();
  }

  private String message(String key, Locale locale, Object... args) {
    return messages.getMessage(
        key, args, "fr".equals(locale.getLanguage()) ? Locale.FRENCH : Locale.ENGLISH);
  }

  private byte[] seal(Map<String, String> record) {
    try {
      return sealer.seal(json.writeValueAsString(record));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, String> open(byte[] sealed) {
    try {
      return json.readValue(
          sealer.open(sealed), new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
