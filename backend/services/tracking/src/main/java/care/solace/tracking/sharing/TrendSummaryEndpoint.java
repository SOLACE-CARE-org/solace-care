package care.solace.tracking.sharing;

import care.solace.audit.AuditEntry;
import care.solace.audit.AuditLog;
import care.solace.contracts.tracking.v1.GetTrendSummaryRequest;
import care.solace.contracts.tracking.v1.GetTrendSummaryResponse;
import care.solace.contracts.tracking.v1.TrendLine;
import care.solace.contracts.tracking.v1.TrendSummaryServiceGrpc;
import care.solace.coresettings.CoreSettings;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.sync.Sync;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * AI asks for a pre-session brief's trend summary (trend_summary_service.proto; D56). Only what the
 * seeker shared with this booking's specialist, recorded after the grant, over the last 14 days;
 * never notes. Each answer with data is audited as a read on the specialist's behalf.
 */
@Service
class TrendSummaryEndpoint extends TrendSummaryServiceGrpc.TrendSummaryServiceImplBase {

  static final int WINDOW = 14;

  private final CoreSettings core;
  private final Sharing sharing;
  private final Sync sync;
  private final ElementLibrary library;
  private final AuditLog audit;
  private final Clock clock;

  TrendSummaryEndpoint(
      CoreSettings core,
      Sharing sharing,
      Sync sync,
      ElementLibrary library,
      AuditLog audit,
      Clock clock) {
    this.core = core;
    this.sharing = sharing;
    this.sync = sync;
    this.library = library;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  public void getTrendSummary(
      GetTrendSummaryRequest request, StreamObserver<GetTrendSummaryResponse> responses) {
    UUID seeker;
    UUID specialist;
    try {
      seeker = UUID.fromString(request.getSeekerSubjectId());
      specialist = UUID.fromString(request.getSpecialistSubjectId());
    } catch (IllegalArgumentException e) {
      responses.onError(Status.INVALID_ARGUMENT.withDescription("ids").asRuntimeException());
      return;
    }
    var grant = sharing.active(seeker, specialist);
    if (grant.isEmpty()) {
      responses.onNext(GetTrendSummaryResponse.newBuilder().setShared(false).build());
      responses.onCompleted();
      return;
    }
    boolean fr = "fr".equals(request.getLocale());
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Africa/Douala")));
    int window = (int) core.number("solace.tracking.trend-window", WINDOW);
    LocalDate from = today.minusDays(window - 1);
    Set<String> codes =
        library.all().stream()
            .filter(e -> !e.isNote())
            .map(ElementLibrary.Element::code)
            .collect(Collectors.toSet());
    var observations =
        sync.liveWithNotes(seeker, codes, from, false).stream()
            .filter(o -> !o.recordedAt().isBefore(grant.get().grantedAt()))
            .toList();
    Map<String, List<Sync.Observation>> byElement = new LinkedHashMap<>();
    for (var o : observations) {
      byElement.computeIfAbsent(o.elementCode(), k -> new java.util.ArrayList<>()).add(o);
    }
    var reply = GetTrendSummaryResponse.newBuilder().setShared(true);
    for (var e : byElement.entrySet()) {
      var element = library.latest(e.getKey()).orElse(null);
      if (element == null) {
        continue;
      }
      var days = e.getValue().stream().map(Sync.Observation::observedOn).distinct().count();
      var line =
          TrendLine.newBuilder()
              .setElementCode(element.code())
              .setElementName(name(element, fr))
              .setDaysLogged((int) days)
              .setWindowDays(window);
      switch (element.valueType()) {
        case INT, MINUTES -> {
          double avg =
              e.getValue().stream()
                  .map(o -> o.value().integer())
                  .filter(java.util.Objects::nonNull)
                  .mapToInt(Integer::intValue)
                  .average()
                  .orElse(0);
          line.setAverage(Math.round(avg * 10) / 10.0)
              .setUnit(
                  element.valueType() == ElementLibrary.ValueType.MINUTES ? "MINUTES" : "SCALE");
        }
        case BOOL -> {
          long yes =
              e.getValue().stream().filter(o -> Boolean.TRUE.equals(o.value().bool())).count();
          line.setMostOften(String.valueOf(yes)).setUnit("YES_NO");
        }
        default -> {
          Map<String, Integer> counts = new HashMap<>();
          for (var o : e.getValue()) {
            String v =
                o.value().text() != null ? o.value().text() : String.valueOf(o.value().date());
            counts.merge(v, 1, Integer::sum);
          }
          String top =
              counts.entrySet().stream()
                  .max(Map.Entry.comparingByValue())
                  .map(Map.Entry::getKey)
                  .orElse("");
          line.setMostOften(valueLabel(element, top, fr)).setUnit("CHOICE");
        }
      }
      reply.addLines(line);
    }
    audit.record(
        new AuditEntry(
            new AuditEntry.Actor(AuditEntry.ActorType.SPECIALIST, specialist.toString(), List.of()),
            "tracking.brief-summary.read",
            "booking",
            request.getBookingId(),
            seeker.toString(),
            AuditEntry.Outcome.ALLOWED,
            null,
            null));
    responses.onNext(reply.build());
    responses.onCompleted();
  }

  private static String name(ElementLibrary.Element e, boolean fr) {
    return e.labels().path(fr ? "fr" : "en").path("name").asText(e.code());
  }

  private static String valueLabel(ElementLibrary.Element e, String value, boolean fr) {
    return e.labels().path(fr ? "fr" : "en").path("values").path(value).asText(value);
  }
}
