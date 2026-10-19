package care.solace.core.healthcheck;

import care.solace.audit.AuditEntry;
import care.solace.audit.AuditLog;
import care.solace.contracts.core.v1.BriefSourceServiceGrpc;
import care.solace.contracts.core.v1.GetBriefSourcesRequest;
import care.solace.contracts.core.v1.GetBriefSourcesResponse;
import care.solace.contracts.core.v1.ScreenerScore;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.catalog.Catalogue;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.privacy.ConsentCheck;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * What the pre-session brief may show for one booking (brief_source_service.proto; D56). The health
 * check part is a plain summary built by rules, because no AI reads health check answers (D8).
 * Answers come only when the seeker shared them for this booking; the read is audited as the
 * specialist's.
 */
@Service
class BriefSourceEndpoint extends BriefSourceServiceGrpc.BriefSourceServiceImplBase {

  private final BookingRecords bookings;
  private final Responses responses;
  private final Versions versions;
  private final ConsentCheck consents;
  private final Catalogue catalogue;
  private final MessageSource messages;
  private final AuditLog audit;

  BriefSourceEndpoint(
      BookingRecords bookings,
      Responses responses,
      Versions versions,
      ConsentCheck consents,
      Catalogue catalogue,
      MessageSource messages,
      AuditLog audit) {
    this.bookings = bookings;
    this.responses = responses;
    this.versions = versions;
    this.consents = consents;
    this.catalogue = catalogue;
    this.messages = messages;
    this.audit = audit;
  }

  @Override
  public void getBriefSources(
      GetBriefSourcesRequest request, StreamObserver<GetBriefSourcesResponse> out) {
    UUID bookingId;
    UUID specialist;
    try {
      bookingId = UUID.fromString(request.getBookingId());
      specialist = UUID.fromString(request.getSpecialistSubjectId());
    } catch (IllegalArgumentException e) {
      out.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    var booking = bookings.find(bookingId).filter(b -> b.specialistId().equals(specialist));
    if (booking.isEmpty()) {
      out.onNext(GetBriefSourcesResponse.newBuilder().setAllowed(false).build());
      out.onCompleted();
      return;
    }
    var b = booking.get();
    Locale locale = "fr".equals(request.getLocale()) ? Locale.FRENCH : Locale.ENGLISH;
    boolean guest = b.seekerAccountId() == null;
    UUID subject = guest ? b.guestContactId() : b.seekerAccountId();
    long before =
        bookings.forSubject(subject).stream()
            .filter(x -> x.specialistId().equals(specialist) && x.status().equals("COMPLETED"))
            .count();
    var reply =
        GetBriefSourcesResponse.newBuilder()
            .setAllowed(true)
            .setSeekerSubjectId(subject == null ? "" : subject.toString())
            .setGuest(guest)
            .setFirstSession(before == 0)
            .setStartsAt(b.start().toString())
            .setModality(b.modality())
            .setSessionLanguage(b.sessionLanguage());
    if (!guest) {
      reply
          .setTrackingSharingConsent(consents.granted(b.seekerAccountId(), "SPECIALIST_SHARING"))
          .setScribeConsent(consents.granted(b.seekerAccountId(), "AI_SCRIBE"));
    }
    var shared = responses.sharedWith(bookingId);
    if (shared.isPresent()) {
      var r = shared.get();
      reply
          .setHealthCheckShared(true)
          .setHealthCheckSharedAt(r.completedAt() == null ? "" : r.completedAt().toString())
          .setHealthCheckSummary(summary(r, locale))
          .setHealthCheckSafetyStop(r.safetyStopped());
      if (r.state().scores() != null) {
        for (var s : r.state().scores()) {
          reply.addScores(
              ScreenerScore.newBuilder()
                  .setScreener(s.screener())
                  .setTotal(s.total())
                  .setBand(s.band()));
        }
      }
      audit.record(
          new AuditEntry(
              new AuditEntry.Actor(
                  AuditEntry.ActorType.SPECIALIST, specialist.toString(), List.of()),
              "health-check.brief.read",
              "health-check-response",
              r.id().toString(),
              subject == null ? null : subject.toString(),
              AuditEntry.Outcome.ALLOWED,
              null,
              null));
    }
    out.onNext(reply.build());
    out.onCompleted();
  }

  /** "Help with: … For … Affects daily life: … Suggested: …", from the answers by rules. */
  String summary(Responses.Response r, Locale locale) {
    var v = versions.require(r.versionId());
    boolean fr = locale.getLanguage().equals("fr");
    List<String> parts = new ArrayList<>();
    String topics = labels(v.questions(), r, "topics", fr);
    if (!topics.isEmpty()) {
      parts.add(messages.getMessage("brief.hc.help-with", new Object[] {topics}, locale));
    }
    String duration = labels(v.questions(), r, "duration", fr);
    if (!duration.isEmpty()) {
      parts.add(messages.getMessage("brief.hc.for", new Object[] {duration}, locale));
    }
    String impact = labels(v.questions(), r, "impact", fr);
    if (!impact.isEmpty()) {
      parts.add(messages.getMessage("brief.hc.affects", new Object[] {impact}, locale));
    }
    String top = r.state().specialty();
    if (top != null) {
      String person = catalogue.professionFor(top, locale).orElse(top).toLowerCase(locale);
      String also = r.state().alsoFit();
      parts.add(
          also == null
              ? messages.getMessage("brief.hc.suggested", new Object[] {person}, locale)
              : messages.getMessage(
                  "brief.hc.suggested-also",
                  new Object[] {
                    person, catalogue.professionFor(also, locale).orElse(also).toLowerCase(locale)
                  },
                  locale));
    }
    return String.join(" ", parts);
  }

  private static String labels(
      List<Question> questions, Responses.Response r, String code, boolean fr) {
    var chosen = r.state().answers().getOrDefault(code, List.of());
    return questions.stream()
        .filter(q -> q.code().equals(code))
        .findFirst()
        .map(
            q ->
                q.options().stream()
                    .filter(o -> chosen.contains(o.code()))
                    .map(
                        o ->
                            (fr ? o.labelFr() : o.labelEn())
                                .toLowerCase(fr ? Locale.FRENCH : Locale.ENGLISH))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""))
        .orElse("");
  }
}
