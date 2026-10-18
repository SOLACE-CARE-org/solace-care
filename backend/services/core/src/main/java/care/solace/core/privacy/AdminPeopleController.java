package care.solace.core.privacy;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.crisis.CrisisSummaries;
import care.solace.core.guests.GuestDirectory;
import care.solace.core.identity.Account;
import care.solace.core.identity.MemberDirectory;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * People and data requests (admin screens 9 and 10; tag admin-people; D45). Contacts stay masked
 * until a logged reveal; every view of a person's record is audited. No screen here shows tracking
 * entries, notes, health check answers or transcripts.
 */
@RestController
class AdminPeopleController {

  private static final String AREA = "people";
  private static final int NEWEST = 50;
  private static final java.util.regex.Pattern PHONE =
      java.util.regex.Pattern.compile("^\\+?[0-9][0-9 ]{6,19}$");

  private final StaffAccess access;
  private final MemberDirectory members;
  private final GuestDirectory guests;
  private final BookingRecords bookings;
  private final CrisisSummaries crisis;
  private final SpecialistOffers specialists;
  private final Consents consents;
  private final DataRights rights;
  private final JdbcClient db;
  private final AuditLog audit;
  private final Clock clock;

  AdminPeopleController(
      StaffAccess access,
      MemberDirectory members,
      GuestDirectory guests,
      BookingRecords bookings,
      CrisisSummaries crisis,
      SpecialistOffers specialists,
      Consents consents,
      DataRights rights,
      JdbcClient db,
      AuditLog audit,
      Clock clock) {
    this.access = access;
    this.members = members;
    this.guests = guests;
    this.bookings = bookings;
    this.crisis = crisis;
    this.specialists = specialists;
    this.consents = consents;
    this.rights = rights;
    this.db = db;
    this.audit = audit;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Row(
      UUID id,
      String ref,
      String type,
      String role,
      String status,
      String contact,
      String appLanguage,
      int bookings,
      Instant since,
      List<String> openRequests) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DataRequest(
      UUID id,
      String kind,
      String status,
      Instant requestedAt,
      Instant dueAt,
      Instant completedAt) {}

  record PersonBooking(
      UUID id,
      String reference,
      Instant startsAt,
      String specialist,
      String modality,
      String sessionLanguage,
      String status,
      long price) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Consent(String purpose, boolean granted, String version, Instant recordedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PersonView(
      UUID id,
      String ref,
      String type,
      String role,
      String status,
      String contact,
      String appLanguage,
      int bookings,
      Instant since,
      List<String> openRequests,
      List<PersonBooking> bookingList,
      List<Consent> consents,
      List<DataRequest> dataRequests,
      int openCrisisAlerts,
      boolean canErase) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Contact(String phone, String email) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  /** A member or a guest, as one shape. */
  private record Person(
      UUID id,
      boolean guest,
      Account.Type type,
      Account.Status status,
      Locale language,
      String phone,
      String email,
      Instant since) {}

  @GetMapping("/admin/v1/people")
  List<Row> search(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) @Size(max = 120) String q,
      @RequestParam(required = false) @Pattern(regexp = "ACCOUNT|GUEST") String type) {
    access.require(token, AREA, Level.VIEW);
    List<Person> found = new ArrayList<>();
    String query = q == null ? "" : q.trim();
    if (query.isEmpty()) {
      if (!"GUEST".equals(type)) {
        members.newest(NEWEST).forEach(m -> found.add(person(m)));
      }
      if (!"ACCOUNT".equals(type)) {
        guests.newest(NEWEST).forEach(g -> found.add(person(g)));
      }
      found.sort((a, b) -> b.since().compareTo(a.since()));
    } else if (query.contains("@")) {
      members.byEmail(query).ifPresent(m -> found.add(person(m)));
    } else if (PHONE.matcher(query).matches()) {
      String e164 = e164(query);
      members.byPhone(e164).ifPresent(m -> found.add(person(m)));
      guests.byPhone(e164).ifPresent(g -> found.add(person(g)));
    } else {
      bookings.byReference(query).flatMap(b -> find(b.subjectId())).ifPresent(found::add);
    }
    return found.stream()
        .filter(p -> type == null || (type.equals("GUEST") == p.guest()))
        .limit(NEWEST)
        .map(this::row)
        .toList();
  }

  @GetMapping("/admin/v1/people/{personId}")
  PersonView person(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID personId, Locale locale) {
    access.require(token, AREA, Level.VIEW);
    Person p = find(personId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    // A person's record is personal data: every view is audited (Admin Portal Spec).
    audit.read("people.record.read", "person", personId.toString(), personId.toString());
    return record(p, locale);
  }

  @PostMapping("/admin/v1/people/{personId}/contact-reveal")
  Contact reveal(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID personId,
      @Valid @RequestBody Reason request) {
    access.require(token, AREA, Level.VIEW);
    Person p = find(personId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    audit.record(
        new care.solace.audit.AuditEntry(
            audit.currentActor(),
            "people.contact.reveal",
            "person",
            personId.toString(),
            personId.toString(),
            care.solace.audit.AuditEntry.Outcome.ALLOWED,
            request.reason().trim(),
            Map.of()));
    return new Contact(p.phone(), p.email());
  }

  @PostMapping("/admin/v1/people/{personId}/data-export")
  @ResponseStatus(HttpStatus.ACCEPTED)
  DataRequest export(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID personId,
      @Valid @RequestBody Reason request) {
    access.require(token, AREA, Level.CHANGE);
    Person p = find(personId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (p.guest() || p.status() == Account.Status.ERASED) {
      throw new SolaceException(CommonError.CONFLICT);
    }
    var r = rights.requestExport(personId);
    audit.adminAction(
        "privacy.export.prepare", "person", personId.toString(), request.reason().trim(), Map.of());
    return new DataRequest(r.id(), r.kind(), r.status(), r.requestedAt(), r.dueAt(), null);
  }

  @PostMapping("/admin/v1/people/{personId}/erasure")
  PersonView erase(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID personId,
      @Valid @RequestBody Reason request,
      Locale locale) {
    access.require(token, AREA, Level.CHANGE);
    Person p = find(personId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!canErase(p)) {
      throw new SolaceException(PrivacyError.UPCOMING_SESSIONS);
    }
    if (p.guest()) {
      guests.forget(personId, clock.instant());
    } else {
      rights.erase(new Account(personId, p.type(), p.status(), p.language(), null));
    }
    audit.adminAction(
        "privacy.erasure.staff", "person", personId.toString(), request.reason().trim(), Map.of());
    return record(find(personId).orElseThrow(), locale);
  }

  // --- Shapes -----------------------------------------------------------------------------------

  private Optional<Person> find(UUID id) {
    return members.byId(id).map(this::person).or(() -> guests.byId(id).map(this::person));
  }

  private Person person(MemberDirectory.Member m) {
    return new Person(
        m.id(), false, m.type(), m.status(), m.appLanguage(), m.phone(), m.email(), m.createdAt());
  }

  private Person person(GuestDirectory.Guest g) {
    return new Person(g.id(), true, null, null, g.locale(), g.phone(), null, g.createdAt());
  }

  private Row row(Person p) {
    return new Row(
        p.id(),
        BookingRecords.personRef(p.id(), p.guest()),
        p.guest() ? "GUEST" : "ACCOUNT",
        p.guest() ? null : p.type().name(),
        p.guest() ? (p.phone() == null ? "ERASED" : "ACTIVE") : p.status().name(),
        mask(p),
        p.language().getLanguage(),
        bookings.forSubject(p.id()).size(),
        p.since(),
        openRequests(p));
  }

  private PersonView record(Person p, Locale locale) {
    var row = row(p);
    Map<UUID, String> names = new HashMap<>();
    var list =
        bookings.forSubject(p.id()).stream()
            .map(
                b ->
                    new PersonBooking(
                        b.id(),
                        "BK-"
                            + b.id()
                                .toString()
                                .replace("-", "")
                                .substring(0, 8)
                                .toUpperCase(Locale.ROOT),
                        b.start(),
                        names.computeIfAbsent(
                            b.specialistId(),
                            id ->
                                specialists
                                    .summary(id, locale)
                                    .map(SpecialistOffers.Summary::fullName)
                                    .orElse("")),
                        b.modality(),
                        b.sessionLanguage(),
                        b.status(),
                        b.price()))
            .toList();
    List<Consent> consentList =
        p.guest()
            ? List.of()
            : consents.latest(p.id()).values().stream()
                .map(c -> new Consent(c.purpose().name(), c.granted(), c.version(), c.recordedAt()))
                .toList();
    return new PersonView(
        row.id(),
        row.ref(),
        row.type(),
        row.role(),
        row.status(),
        row.contact(),
        row.appLanguage(),
        row.bookings(),
        row.since(),
        row.openRequests(),
        list,
        consentList,
        p.guest() ? List.of() : requests(p.id()),
        crisis.openFor(p.id()),
        canErase(p));
  }

  /** A seeker or a guest with no session ahead; specialists file a data request (D40). */
  private boolean canErase(Person p) {
    if (!p.guest() && (p.type() != Account.Type.SEEKER || p.status() == Account.Status.ERASED)) {
      return false;
    }
    if (p.guest() && p.phone() == null) {
      return false;
    }
    return bookings.nextFor(p.id(), clock.instant()).isEmpty()
        && bookings.forSubject(p.id()).stream()
            .noneMatch(
                b -> b.status().equals("IN_PROGRESS") || b.status().equals("PENDING_PAYMENT"));
  }

  private List<String> openRequests(Person p) {
    if (p.guest()) {
      return List.of();
    }
    return requests(p.id()).stream()
        .filter(r -> r.status().equals("PREPARING") || r.status().equals("REQUESTED"))
        .map(DataRequest::kind)
        .distinct()
        .toList();
  }

  private List<DataRequest> requests(UUID accountId) {
    return db.sql(
            """
            SELECT id, kind, status, requested_at, due_at, completed_at FROM data_request
             WHERE account_id = :a ORDER BY requested_at DESC
            """)
        .param("a", accountId)
        .query(
            (rs, n) -> {
              Timestamp done = rs.getTimestamp("completed_at");
              return new DataRequest(
                  rs.getObject("id", UUID.class),
                  rs.getString("kind"),
                  rs.getString("status"),
                  rs.getTimestamp("requested_at").toInstant(),
                  rs.getTimestamp("due_at").toInstant(),
                  done == null ? null : done.toInstant());
            })
        .list();
  }

  /** "+237 6•• •• •• 34" or "a•••••@gmail.com" (Admin Portal Spec, Masked contacts). */
  static String mask(Person p) {
    if (p.phone() != null) {
      String digits = p.phone();
      int keep = 2;
      String country =
          digits.startsWith("+237")
              ? "+237 "
              : digits.substring(0, Math.min(4, digits.length())) + " ";
      String local =
          digits.startsWith("+237")
              ? digits.substring(4)
              : digits.substring(Math.min(4, digits.length()));
      if (local.length() <= keep + 1) {
        return country + "••";
      }
      return country + local.charAt(0) + "•• •• •• " + local.substring(local.length() - keep);
    }
    if (p.email() != null) {
      int at = p.email().indexOf('@');
      return at <= 0 ? "•••" : p.email().charAt(0) + "•••••" + p.email().substring(at);
    }
    return "";
  }

  private static String e164(String typed) {
    String digits = typed.replace(" ", "");
    if (digits.startsWith("+")) {
      return digits;
    }
    // Cameroonian numbers are typed without the country code.
    return digits.length() == 9 ? "+237" + digits : "+" + digits;
  }
}
