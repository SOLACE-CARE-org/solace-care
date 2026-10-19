package care.solace.ai.scribe;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.safety.SafetyChecks;
import care.solace.events.Events;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scribe as a LangGraph4j graph (System Design, Runtime; D57): transcribe the session audio on
 * a local model, screen the transcript for risk signals, draft the note, store it. The human step
 * comes after: the draft waits for the specialist to edit and sign, and nothing is final until
 * then. A failed transcription or draft still stores an empty draft for the specialist to write.
 */
@Component
class ScribePipeline {

  /** What the pipeline needs, from core.session.ended. */
  record Session(
      UUID bookingId,
      UUID specialistId,
      UUID seekerSubjectId,
      String audioKey,
      String sessionLanguage) {}

  private final AudioStore audio;
  private final ModelGateway gateway;
  private final Features features;
  private final SafetyChecks safety;
  private final NoteDrafter drafter;
  private final Notes notes;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final CompiledGraph<AgentState> graph;

  ScribePipeline(
      AudioStore audio,
      ModelGateway gateway,
      Features features,
      SafetyChecks safety,
      NoteDrafter drafter,
      Notes notes,
      ApplicationEventPublisher events,
      TransactionTemplate transactions)
      throws GraphStateException {
    this.audio = audio;
    this.gateway = gateway;
    this.features = features;
    this.safety = safety;
    this.drafter = drafter;
    this.notes = notes;
    this.events = events;
    this.transactions = transactions;
    this.graph =
        new StateGraph<>(AgentState::new)
            .addNode("transcribe", node_async(this::transcribe))
            .addNode("screen", node_async(this::screen))
            .addNode("draft", node_async(this::draft))
            .addNode("store", node_async(this::store))
            .addEdge(START, "transcribe")
            .addConditionalEdges(
                "transcribe",
                edge_async(s -> s.value("transcript").isPresent() ? "screen" : "store"),
                Map.of("screen", "screen", "store", "store"))
            .addEdge("screen", "draft")
            .addEdge("draft", "store")
            .addEdge("store", END)
            .compile();
  }

  /** Runs the whole pipeline for one session; returns the note's id. */
  UUID run(Session s) {
    Map<String, Object> input = new HashMap<>();
    input.put("bookingId", s.bookingId().toString());
    input.put("specialistId", s.specialistId().toString());
    input.put("seekerSubjectId", s.seekerSubjectId().toString());
    input.put("audioKey", s.audioKey());
    input.put("language", "fr".equals(s.sessionLanguage()) ? "fr" : "en");
    AgentState done =
        graph.invoke(GraphInput.args(input), RunnableConfig.builder().build()).orElseThrow();
    return UUID.fromString(done.<String>value("noteId").orElseThrow());
  }

  // --- Nodes ------------------------------------------------------------------------------------

  private Map<String, Object> transcribe(AgentState s) {
    var live = features.live(Feature.SESSION_SCRIBE);
    if (live.isEmpty()) {
      // The scribe is not switched on: keep nothing, the specialist writes the note.
      return Map.of();
    }
    String language = s.<String>value("language").orElse("en");
    Optional<String> text =
        audio
            .read(s.<String>value("audioKey").orElseThrow())
            .flatMap(
                bytes ->
                    gateway.transcribe(
                        UUID.fromString(s.<String>value("seekerSubjectId").orElseThrow()),
                        live.get().version(),
                        bytes,
                        "session.ogg",
                        language));
    return text.<Map<String, Object>>map(t -> Map.of("transcript", t)).orElse(Map.of());
  }

  private Map<String, Object> screen(AgentState s) {
    Locale locale = Locale.forLanguageTag(s.<String>value("language").orElse("en"));
    return Map.of("risk", safety.signals(s.<String>value("transcript").orElseThrow(), locale));
  }

  private Map<String, Object> draft(AgentState s) {
    var live = features.live(Feature.SESSION_SCRIBE);
    if (live.isEmpty()) {
      return Map.of();
    }
    Locale locale = Locale.forLanguageTag(s.<String>value("language").orElse("en"));
    return drafter
        .draft(
            live.get(),
            UUID.fromString(s.<String>value("seekerSubjectId").orElseThrow()),
            s.<String>value("transcript").orElseThrow(),
            "SOAP",
            locale)
        .<Map<String, Object>>map(
            sections -> Map.of("sections", sections, "promptVersion", live.get().version()))
        .orElse(Map.of());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> store(AgentState s) {
    List<Notes.Section> sections =
        s.<List<Notes.Section>>value("sections")
            .orElseGet(
                () -> Notes.keys("SOAP").stream().map(k -> new Notes.Section(k, "")).toList());
    UUID bookingId = UUID.fromString(s.<String>value("bookingId").orElseThrow());
    UUID specialist = UUID.fromString(s.<String>value("specialistId").orElseThrow());
    UUID seeker = UUID.fromString(s.<String>value("seekerSubjectId").orElseThrow());
    UUID noteId =
        transactions.execute(
            tx -> {
              UUID id =
                  notes.store(
                      new Notes.Draft(
                          bookingId,
                          specialist,
                          seeker,
                          "SOAP",
                          sections,
                          s.value("sections").isPresent(),
                          s.<Boolean>value("risk").orElse(false),
                          s.<String>value("promptVersion").orElse(null),
                          s.<String>value("transcript").orElse(null),
                          s.<String>value("language").orElse("en")));
              events.publishEvent(
                  Events.envelope(
                      "ai.note.drafted",
                      1,
                      specialist.toString(),
                      s.<String>value("language").orElse("en"),
                      new Drafted(id, bookingId, specialist)));
              return id;
            });
    return Map.of("noteId", noteId.toString());
  }

  /** ai.note.drafted v1: ids only. */
  record Drafted(UUID noteId, UUID bookingId, UUID specialistId) {}
}
