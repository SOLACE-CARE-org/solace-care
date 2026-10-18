package care.solace.core.bookings;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bookings and sessions for staff (admin screens 7 and 8; tag admin-bookings). People appear by a
 * pseudonymous reference, never by name or contact (D41). Every change and export is audited.
 */
@RestController
class AdminBookingsController {

  private static final String AREA = "bookings";

  private final StaffAccess access;
  private final AdminBookings bookings;
  private final BookingStore store;
  private final DisputeStore disputes;
  private final BookingMoney money;
  private final BookingAttendance attendance;
  private final SpecialistOffers specialists;
  private final AuditLog audit;

  AdminBookingsController(
      StaffAccess access,
      AdminBookings bookings,
      BookingStore store,
      DisputeStore disputes,
      BookingMoney money,
      BookingAttendance attendance,
      SpecialistOffers specialists,
      AuditLog audit) {
    this.access = access;
    this.bookings = bookings;
    this.store = store;
    this.disputes = disputes;
    this.money = money;
    this.attendance = attendance;
    this.specialists = specialists;
    this.audit = audit;
  }

  // --- Views ------------------------------------------------------------------------------------

  record SpecialistRef(UUID id, String fullName) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record BookingRow(
      UUID id,
      String reference,
      Instant startsAt,
      Instant endsAt,
      SpecialistRef specialist,
      String personRef,
      String modality,
      String sessionLanguage,
      long price,
      String status,
      String cancelledBy,
      String waiting,
      boolean disputed) {}

  record PolicyView(
      int version, int seekerNoShowRefundPercent, int specialistNoShowRefundPercent) {}

  record MoneyView(
      String method, String phone, long amount, String status, long held, long refunded) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DisputeJson(
      Instant openedAt,
      String openedBy,
      String note,
      String outcome,
      String resolvedBy,
      Instant resolvedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TimelineEntry(Instant at, String kind, String role, Long amount, String detail) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record BookingDetail(
      UUID id,
      String reference,
      Instant startsAt,
      Instant endsAt,
      SpecialistRef specialist,
      String personRef,
      String modality,
      String sessionLanguage,
      long price,
      String status,
      String cancelledBy,
      String waiting,
      boolean disputed,
      Instant createdAt,
      String source,
      int reschedules,
      PolicyView policy,
      MoneyView money,
      DisputeJson dispute,
      List<TimelineEntry> timeline,
      boolean canCancel) {}

  record DisputeRequest(@NotBlank @Size(max = 500) String note) {}

  record Resolution(
      @NotNull AdminBookings.Outcome outcome,
      @Min(0) Long amount,
      @Size(max = 500) String reason) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  // --- Search -----------------------------------------------------------------------------------

  @GetMapping("/admin/v1/bookings")
  List<BookingRow> search(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) UUID specialistId,
      @RequestParam(required = false) @Size(max = 40) String q,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    return rows(new AdminBookings.Query(status, from, to, language, specialistId, q), locale);
  }

  @GetMapping(value = "/admin/v1/bookings/export", produces = "text/csv")
  String export(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) UUID specialistId,
      @RequestParam(required = false) @Size(max = 40) String q,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    var csv =
        new StringBuilder(
            "reference,starts_at,ends_at,specialist,person,modality,language,price,status,disputed\n");
    for (var r :
        rows(new AdminBookings.Query(status, from, to, language, specialistId, q), locale)) {
      csv.append(
              String.join(
                  ",",
                  cell(r.reference()),
                  r.startsAt().toString(),
                  r.endsAt().toString(),
                  cell(r.specialist().fullName()),
                  cell(r.personRef()),
                  r.modality(),
                  cell(r.sessionLanguage()),
                  String.valueOf(r.price()),
                  r.status(),
                  String.valueOf(r.disputed())))
          .append('\n');
    }
    audit.adminAction(
        "bookings.export",
        "booking",
        "search",
        status == null ? Map.of() : Map.of("status", status));
    return csv.toString();
  }

  private List<BookingRow> rows(AdminBookings.Query query, Locale locale) {
    var found = bookings.search(query);
    Map<UUID, String> names = new HashMap<>();
    return found.stream()
        .map(
            f -> {
              var r = f.row();
              String name =
                  names.computeIfAbsent(r.specialistId(), id -> specialistName(id, locale));
              return new BookingRow(
                  r.id(),
                  AdminBookings.reference(r.id()),
                  r.start(),
                  r.end(),
                  new SpecialistRef(r.specialistId(), name),
                  r.info().personRef(),
                  r.modality(),
                  r.sessionLanguage(),
                  r.price(),
                  r.status(),
                  r.cancelledBy(),
                  f.waiting(),
                  f.disputed());
            })
        .toList();
  }

  private String specialistName(UUID id, Locale locale) {
    return specialists.summary(id, locale).map(SpecialistOffers.Summary::fullName).orElse("");
  }

  // --- One booking ------------------------------------------------------------------------------

  @GetMapping("/admin/v1/bookings/{bookingId}")
  BookingDetail booking(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    return detail(bookingId, locale);
  }

  @PostMapping("/admin/v1/bookings/{bookingId}/disputes")
  @ResponseStatus(HttpStatus.CREATED)
  BookingDetail openDispute(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody DisputeRequest request,
      Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    bookings.openDispute(bookingId, request.note(), staff);
    audit.adminAction("bookings.dispute.open", "booking", bookingId.toString(), Map.of());
    return detail(bookingId, locale);
  }

  @PostMapping("/admin/v1/bookings/{bookingId}/dispute-resolution")
  BookingDetail resolve(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody Resolution request,
      Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var refund =
        bookings.resolve(bookingId, request.outcome(), request.amount(), request.reason(), staff);
    var details = new HashMap<String, String>();
    details.put("outcome", request.outcome().name());
    refund.ifPresent(id -> details.put("refundId", id.toString()));
    if (request.amount() != null) {
      details.put("amountChanged", "true");
    }
    audit.adminAction("bookings.dispute.resolve", "booking", bookingId.toString(), details);
    return detail(bookingId, locale);
  }

  @PostMapping("/admin/v1/bookings/{bookingId}/cancellation")
  BookingDetail cancel(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody Reason request,
      Locale locale) {
    access.require(token, AREA, Level.CHANGE);
    if (!bookings.canCancel(bookings.booking(bookingId))) {
      throw new SolaceException(BookingError.NOT_CANCELLABLE);
    }
    bookings.cancel(bookingId);
    audit.adminAction("bookings.cancel", "booking", bookingId.toString(), Map.of("reason", "set"));
    return detail(bookingId, locale);
  }

  private BookingDetail detail(UUID bookingId, Locale locale) {
    var r = bookings.booking(bookingId);
    var policy = store.policy(r.policyVersion());
    var m = money.money(bookingId);
    var dispute = bookings.dispute(bookingId);
    var waiting = attendance.waiting(List.of(bookingId)).get(bookingId);
    return new BookingDetail(
        r.id(),
        AdminBookings.reference(r.id()),
        r.start(),
        r.end(),
        new SpecialistRef(r.specialistId(), specialistName(r.specialistId(), locale)),
        r.info().personRef(),
        r.modality(),
        r.sessionLanguage(),
        r.price(),
        r.status(),
        r.cancelledBy(),
        waiting,
        bookings.disputed(bookingId),
        store.times(bookingId).createdAt(),
        r.source(),
        r.reschedules(),
        new PolicyView(
            policy.version(),
            policy.seekerNoShowRefundPercent(),
            policy.specialistNoShowRefundPercent()),
        m.map(
                x ->
                    new MoneyView(
                        x.method(),
                        x.maskedPhone(),
                        x.amount(),
                        x.status(),
                        x.held(),
                        x.refunded()))
            .orElse(null),
        dispute
            .map(
                d ->
                    new DisputeJson(
                        d.openedAt(),
                        d.openedBy(),
                        d.note(),
                        d.outcome(),
                        d.resolvedBy(),
                        d.resolvedAt()))
            .orElse(null),
        timeline(r, m.orElse(null)),
        bookings.canCancel(r));
  }

  /** What happened to the booking, oldest first; people by role only (D36). */
  private List<TimelineEntry> timeline(BookingStore.Row r, BookingMoney.Money m) {
    var times = store.times(r.id());
    var out = new ArrayList<TimelineEntry>();
    out.add(new TimelineEntry(times.createdAt(), "BOOKED", null, r.price(), null));
    if (times.confirmedAt() != null) {
      out.add(
          new TimelineEntry(
              times.confirmedAt(), "PAID", null, m == null ? null : m.amount(), null));
    }
    for (var e : attendance.events(r.id())) {
      String kind = e.kind().equals("ROOM_FINISHED") ? "ROOM_CLOSED" : e.kind();
      out.add(new TimelineEntry(e.at(), kind, e.role(), null, null));
    }
    attendance
        .openFlag(r.id())
        .ifPresent(at -> out.add(new TimelineEntry(at, "FLAGGED", null, null, null)));
    if (times.cancelledAt() != null) {
      out.add(
          new TimelineEntry(
              times.cancelledAt(), "CANCELLED", r.cancelledBy(), r.refundAmount(), null));
    }
    disputes
        .latest(r.id())
        .ifPresent(
            d -> {
              out.add(new TimelineEntry(d.openedAt(), "DISPUTE_OPENED", "STAFF", null, d.note()));
              if (!d.open()) {
                out.add(
                    new TimelineEntry(
                        d.resolvedAt(),
                        "DISPUTE_RESOLVED",
                        "STAFF",
                        d.refundAmount(),
                        d.outcome()));
              }
            });
    if (m != null) {
      for (var refund : m.refunds()) {
        out.add(
            new TimelineEntry(
                refund.createdAt(), "REFUND", null, refund.amount(), refund.status()));
      }
    }
    out.sort(Comparator.comparing(TimelineEntry::at));
    return out;
  }

  /** A CSV cell, quoted; a leading = + - @ is defused so spreadsheets do not run it (D41). */
  static String cell(String value) {
    if (value == null) {
      return "";
    }
    String safe = value.matches("^[=+\\-@].*") ? "'" + value : value;
    return '"' + safe.replace("\"", "\"\"") + '"';
  }
}
