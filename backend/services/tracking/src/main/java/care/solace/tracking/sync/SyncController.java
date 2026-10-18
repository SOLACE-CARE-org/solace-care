package care.solace.tracking.sync;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.Seekers;
import care.solace.tracking.consent.TrackingConsent;
import care.solace.tracking.elements.ElementLibrary;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Logging from the phone (tag sync; D49). Seekers only, and only with tracking consent. */
@RestController
class SyncController {

  private final Seekers seekers;
  private final TrackingConsent consent;
  private final Sync sync;
  private final ElementLibrary library;
  private final AuditLog audit;

  SyncController(
      Seekers seekers, TrackingConsent consent, Sync sync, ElementLibrary library, AuditLog audit) {
    this.seekers = seekers;
    this.consent = consent;
    this.sync = sync;
    this.library = library;
    this.audit = audit;
  }

  record ElementRef(@NotBlank String code, @NotBlank String version) {}

  record PushEntry(
      @NotNull UUID clientEntryId,
      @NotNull @Valid ElementRef element,
      @NotNull LocalDate observedOn,
      @NotBlank @Size(max = 64) String timeZone,
      @NotNull JsonNode value,
      @NotNull Instant recordedAt) {}

  record DeletionRequest(UUID observationId, UUID clientEntryId) {}

  record PushRequest(
      @NotBlank @Size(max = 100) String deviceId,
      @NotNull @Size(max = 500) List<@Valid PushEntry> entries,
      @NotNull @Size(max = 500) List<DeletionRequest> deletions) {}

  record ObservationView(
      UUID id,
      UUID clientEntryId,
      ElementRef element,
      LocalDate observedOn,
      String timeZone,
      Object value,
      Instant recordedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PullView(
      List<ObservationView> observations, List<UUID> deleted, long cursor, boolean hasMore) {}

  @PostMapping("/v1/tracking/sync/push")
  Sync.PushResult push(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody PushRequest request) {
    UUID user = seekers.require(token);
    consent.require(user);
    for (var d : request.deletions()) {
      if (d.observationId() == null && d.clientEntryId() == null) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    return sync.push(
        user,
        request.deviceId(),
        request.entries().stream()
            .map(
                e ->
                    new Sync.Entry(
                        e.clientEntryId(),
                        e.element().code(),
                        e.element().version(),
                        e.observedOn(),
                        e.timeZone(),
                        e.value(),
                        e.recordedAt()))
            .toList(),
        request.deletions().stream()
            .map(d -> new Sync.Deletion(d.observationId(), d.clientEntryId()))
            .toList());
  }

  /** The owner's own entries; still audited, as every read of tracking entries is. */
  @GetMapping("/v1/tracking/sync/pull")
  PullView pull(@AuthenticationPrincipal Jwt token, @RequestParam(defaultValue = "0") long since) {
    UUID user = seekers.require(token);
    var result = sync.pull(user, Math.max(0, since));
    audit.read("tracking.entries.read", "observations", null, user.toString());
    return new PullView(
        result.live().stream().map(this::view).toList(),
        result.deleted(),
        result.cursor(),
        result.hasMore());
  }

  @DeleteMapping("/v1/tracking/observations/{observationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal Jwt token, @PathVariable UUID observationId) {
    UUID user = seekers.require(token);
    if (sync.deleteOne(user, observationId).isEmpty()) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  private ObservationView view(Sync.Observation o) {
    var element = library.byId(o.elementId()).orElseThrow();
    Object value =
        element.isNote()
            ? o.note()
            : o.value().integer() != null
                ? o.value().integer()
                : o.value().bool() != null
                    ? o.value().bool()
                    : o.value().date() != null ? o.value().date().toString() : o.value().text();
    return new ObservationView(
        o.id(),
        o.clientEntryId(),
        new ElementRef(element.code(), element.version()),
        o.observedOn(),
        o.timeZone(),
        value,
        o.recordedAt());
  }
}
