package care.solace.tracking.sharing;

import care.solace.audit.AuditLog;
import care.solace.errors.SolaceException;
import care.solace.tracking.Seekers;
import care.solace.tracking.TrackingError;
import care.solace.tracking.elements.ElementLibrary;
import care.solace.tracking.sync.Sync;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
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

/**
 * Sharing with a specialist (D52). The seeker grants and revokes; the specialist reads only while a
 * booking is live, only what was recorded after the grant, and notes only with the seeker's own
 * yes. Every read and every refusal is audited.
 */
@RestController
class SharingController {

  private final Seekers seekers;
  private final Sharing sharing;
  private final LiveBookings bookings;
  private final Sync sync;
  private final ElementLibrary library;
  private final AuditLog audit;

  SharingController(
      Seekers seekers,
      Sharing sharing,
      LiveBookings bookings,
      Sync sync,
      ElementLibrary library,
      AuditLog audit) {
    this.seekers = seekers;
    this.sharing = sharing;
    this.bookings = bookings;
    this.sync = sync;
    this.library = library;
    this.audit = audit;
  }

  record GrantRequest(@NotNull UUID specialistId, @NotNull Boolean includesNotes) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SharedEntry(String element, LocalDate observedOn, Object value, Instant recordedAt) {}

  record Shared(Instant since, boolean includesNotes, List<SharedEntry> entries) {}

  @GetMapping("/v1/tracking/shares")
  List<Sharing.Grant> grants(@AuthenticationPrincipal Jwt token) {
    return sharing.grants(seekers.require(token));
  }

  @PostMapping("/v1/tracking/shares")
  @ResponseStatus(HttpStatus.CREATED)
  Sharing.Grant grant(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody GrantRequest request) {
    return sharing.grant(seekers.require(token), request.specialistId(), request.includesNotes());
  }

  @DeleteMapping("/v1/tracking/shares/{grantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@AuthenticationPrincipal Jwt token, @PathVariable UUID grantId) {
    sharing.revoke(seekers.require(token), grantId);
  }

  /** A specialist reads what a seeker shared with them. */
  @GetMapping("/v1/tracking/patients/{seekerId}/entries")
  Shared read(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID seekerId,
      @RequestParam(required = false) LocalDate from) {
    UUID specialist = seekers.requireSpecialist(token);
    var grant = sharing.active(seekerId, specialist);
    if (grant.isEmpty() || !bookings.live(seekerId, specialist)) {
      audit.denied("tracking.shared.read", "observations", null, seekerId.toString());
      throw new SolaceException(TrackingError.NO_ACCESS);
    }
    var g = grant.get();
    var codes = new HashSet<String>();
    for (var e : library.all()) {
      if (!e.isNote() || g.includesNotes()) {
        codes.add(e.code());
      }
    }
    LocalDate since = from != null ? from : LocalDate.of(2000, 1, 1);
    audit.read("tracking.shared.read", "observations", null, seekerId.toString());
    var entries =
        sync.liveWithNotes(seekerId, codes, since, g.includesNotes()).stream()
            .filter(o -> !o.recordedAt().isBefore(g.grantedAt()))
            .map(o -> new SharedEntry(o.elementCode(), o.observedOn(), value(o), o.recordedAt()))
            .toList();
    return new Shared(g.grantedAt(), g.includesNotes(), entries);
  }

  private static Object value(Sync.Observation o) {
    if (o.note() != null) {
      return o.note();
    }
    var v = o.value();
    if (v.integer() != null) {
      return v.integer();
    }
    if (v.bool() != null) {
      return v.bool();
    }
    if (v.date() != null) {
      return v.date().toString();
    }
    return v.text();
  }
}
