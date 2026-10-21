package care.solace.core.crisis;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.guests.GuestAccess;
import care.solace.core.identity.Accounts;
import care.solace.core.reviews.FlaggedReviews;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The crisis queue (admin screen 3) and the on-call rota (tag admin-crisis). Responders see the
 * flagged text and nothing else the person wrote; every view and every revealed number is audited.
 */
@RestController
class CrisisController {

  private static final String AREA = "crisis";

  private final CrisisAlerts alerts;
  private final StaffAccess access;
  private final AuditLog audit;
  private final FlaggedReviews reviews;
  private final BookingRecords bookings;
  private final SpecialistOffers specialists;
  private final Accounts accounts;
  private final GuestAccess guests;
  private final Clock clock;

  private final java.util.List<CallbackContacts> callbacks;

  CrisisController(
      CrisisAlerts alerts,
      StaffAccess access,
      AuditLog audit,
      FlaggedReviews reviews,
      BookingRecords bookings,
      SpecialistOffers specialists,
      Accounts accounts,
      GuestAccess guests,
      java.util.List<CallbackContacts> callbacks,
      Clock clock) {
    this.callbacks = callbacks;
    this.alerts = alerts;
    this.access = access;
    this.audit = audit;
    this.reviews = reviews;
    this.bookings = bookings;
    this.specialists = specialists;
    this.accounts = accounts;
    this.guests = guests;
    this.clock = clock;
  }

  record NextSession(Instant at, String modality, String sessionLanguage, String specialistName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Alert(
      UUID id,
      String status,
      String category,
      String source,
      String language,
      String personRef,
      Instant raisedAt,
      Instant escalatedToBackupAt,
      Instant escalatedToAdminsAt,
      String acknowledgedBy,
      Instant acknowledgedAt,
      String text,
      String contact,
      NextSession nextSession,
      String outcome,
      String note,
      Instant closedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Shift(
      @NotBlank @Size(max = 100) String staffId,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone,
      @Pattern(regexp = "en|fr") String language,
      @NotNull @Pattern(regexp = "PRIMARY|BACKUP|ESCALATION") String role,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Summary(int open, int unacknowledged, Shift primary, Shift backup, Instant lastClosedAt) {}

  record Closure(
      @NotNull
          @Pattern(
              regexp =
                  "SAFE_FOR_NOW|REFERRED_TO_EMERGENCY_SERVICES|NOT_REACHED_FOLLOW_UP|NOT_A_CRISIS")
          String outcome,
      @Size(max = 2000) String note) {}

  record Contact(String phone) {}

  @GetMapping("/admin/v1/crisis/summary")
  Summary summary(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    Instant now = clock.instant();
    return new Summary(
        alerts.count("status <> 'CLOSED'"),
        alerts.count("status = 'OPEN'"),
        alerts.onCall("PRIMARY", now).stream()
            .findFirst()
            .map(CrisisController::shift)
            .orElse(null),
        alerts.onCall("BACKUP", now).stream().findFirst().map(CrisisController::shift).orElse(null),
        alerts.lastClosedAt().orElse(null));
  }

  @GetMapping("/admin/v1/crisis/alerts")
  List<Alert> list(@AuthenticationPrincipal Jwt token, @RequestParam String status) {
    access.require(token, AREA, Level.VIEW);
    if (!List.of("OPEN", "IN_PROGRESS", "CLOSED").contains(status)) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    return alerts.list(status).stream().map(a -> view(a, false, null)).toList();
  }

  /** The flagged text is clinical data: the view is audited before it is read. */
  @GetMapping("/admin/v1/crisis/alerts/{alertId}")
  Alert get(@AuthenticationPrincipal Jwt token, @PathVariable UUID alertId, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    var a = alert(alertId);
    audit.read("crisis.alert.read", "crisis-alert", alertId.toString(), a.subjectId().toString());
    return view(a, true, locale);
  }

  @PostMapping("/admin/v1/crisis/alerts/{alertId}/acknowledgement")
  Alert acknowledge(@AuthenticationPrincipal Jwt token, @PathVariable UUID alertId, Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    alert(alertId);
    if (!alerts.acknowledge(alertId, staff.id(), staff.name())) {
      throw new SolaceException(CrisisError.WRONG_STATE);
    }
    audit.adminAction("crisis.alert.acknowledge", "crisis-alert", alertId.toString(), Map.of());
    return view(alert(alertId), false, locale);
  }

  @PostMapping("/admin/v1/crisis/alerts/{alertId}/contact")
  Contact contact(@AuthenticationPrincipal Jwt token, @PathVariable UUID alertId) {
    access.require(token, AREA, Level.CHANGE);
    var a = alert(alertId);
    audit.read(
        "crisis.contact.reveal", "crisis-alert", alertId.toString(), a.subjectId().toString());
    return new Contact(phone(a).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND)));
  }

  @PostMapping("/admin/v1/crisis/alerts/{alertId}/closure")
  Alert close(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID alertId,
      @Valid @RequestBody Closure body,
      Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    alert(alertId);
    String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
    if (!alerts.close(alertId, body.outcome(), note, staff.id(), staff.name())) {
      throw new SolaceException(CrisisError.WRONG_STATE);
    }
    audit.adminAction(
        "crisis.alert.close",
        "crisis-alert",
        alertId.toString(),
        note == null ? Map.of("outcome", "set") : Map.of("outcome", "set", "note", "set"));
    return view(alert(alertId), false, locale);
  }

  @GetMapping("/admin/v1/crisis/rota")
  List<Shift> rota(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return alerts.rota(clock.instant()).stream().map(CrisisController::shift).toList();
  }

  /** Only a Super admin keeps the rota: it decides who is woken at night. */
  @PutMapping("/admin/v1/crisis/rota")
  List<Shift> saveRota(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody List<@Valid Shift> shifts) {
    var staff = access.require(token, AREA, Level.CHANGE);
    if (!staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    Instant now = clock.instant();
    List<CrisisAlerts.Shift> kept = new ArrayList<>();
    for (Shift s : shifts) {
      if (!s.startsAt().isBefore(s.endsAt()) || !s.endsAt().isAfter(now)) {
        throw new SolaceException(CrisisError.ROTA_INVALID);
      }
      kept.add(
          new CrisisAlerts.Shift(
              null,
              s.staffId(),
              s.name().strip(),
              s.phone(),
              s.language() == null ? "en" : s.language(),
              s.role(),
              s.startsAt(),
              s.endsAt()));
    }
    if (shifts.size() > 200) {
      throw new SolaceException(CrisisError.ROTA_INVALID);
    }
    alerts.replaceRota(kept, now);
    audit.adminAction("crisis.rota.save", "crisis-rota", "rota", Map.of("shifts", "set"));
    return alerts.rota(now).stream().map(CrisisController::shift).toList();
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private CrisisAlerts.Alert alert(UUID id) {
    return alerts.find(id).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private Alert view(CrisisAlerts.Alert a, boolean detail, Locale locale) {
    String text = null;
    String contact = null;
    NextSession next = null;
    if (detail) {
      if ("REVIEW".equals(a.source())) {
        text = reviews.find(a.sourceId()).map(FlaggedReviews.Flagged::text).orElse(null);
      }
      if ("SESSION".equals(a.source())) {
        text = alerts.note(a.id()).orElse(null);
      }
      contact = phone(a).map(CrisisController::mask).orElse(null);
      next =
          bookings
              .nextFor(a.subjectId(), clock.instant())
              .map(
                  b ->
                      new NextSession(
                          b.start(),
                          b.modality(),
                          b.sessionLanguage(),
                          specialists
                              .summary(b.specialistId(), locale)
                              .map(SpecialistOffers.Summary::fullName)
                              .orElse("")))
              .orElse(null);
    }
    return new Alert(
        a.id(),
        a.status(),
        a.category(),
        a.source(),
        a.language(),
        a.personRef(),
        a.raisedAt(),
        a.escalatedBackupAt(),
        a.escalatedAdminsAt(),
        a.acknowledgedByName(),
        a.acknowledgedAt(),
        text,
        contact,
        next,
        a.outcome(),
        a.note(),
        a.closedAt());
  }

  private Optional<String> phone(CrisisAlerts.Alert a) {
    if (!a.guest()) {
      return accounts.phone(a.subjectId());
    }
    // A guest who stopped at the health check's safety question left a number to call back.
    for (CallbackContacts c : callbacks) {
      Optional<String> left = c.phone(a.source(), a.sourceId());
      if (left.isPresent()) {
        return left;
      }
    }
    return guests.phone(a.subjectId());
  }

  private static Shift shift(CrisisAlerts.Shift s) {
    return new Shift(
        s.staffId(), s.name(), s.phone(), s.language(), s.role(), s.startsAt(), s.endsAt());
  }

  /** "+237 6•• •• •• 34", as the queue shows a number before it is revealed. */
  static String mask(String phone) {
    if (phone.startsWith("+237") && phone.length() == 13) {
      return "+237 " + phone.charAt(4) + "•• •• •• " + phone.substring(11);
    }
    return phone.substring(0, Math.min(4, phone.length()))
        + " •• "
        + phone.substring(phone.length() - 2);
  }
}
