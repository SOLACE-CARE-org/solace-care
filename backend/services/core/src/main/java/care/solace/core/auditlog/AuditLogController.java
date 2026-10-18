package care.solace.core.auditlog;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.admin.StaffDirectory;
import care.solace.core.bookings.BookingRecords;
import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log (admin screen 18; tag admin-audit). Append-only: there is no way to change or
 * remove an entry. People appear by role and pseudonymous reference; staff by name (D41, D42).
 */
@RestController
class AuditLogController {

  private static final String AREA = "audit";
  private static final int PAGE = 100;
  private static final int EXPORT = 10_000;

  private final StaffAccess access;
  private final AuditSearch search;
  private final StaffDirectory staff;
  private final Accounts accounts;
  private final SpecialistOffers specialists;
  private final AuditLog audit;

  AuditLogController(
      StaffAccess access,
      AuditSearch search,
      StaffDirectory staff,
      Accounts accounts,
      SpecialistOffers specialists,
      AuditLog audit) {
    this.access = access;
    this.search = search;
    this.staff = staff;
    this.accounts = accounts;
    this.specialists = specialists;
    this.audit = audit;
  }

  record Actor(String type, String name, List<String> roles) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Target(String type, String id, String label) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Entry(
      long id,
      Instant at,
      Actor actor,
      String action,
      Target target,
      Map<String, String> details,
      String reason,
      String outcome) {}

  @GetMapping("/admin/v1/audit")
  List<Entry> list(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) AuditSearch.Group group,
      @RequestParam(required = false) @Size(max = 80) String q,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) Long before,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    return entries(query(group, q, from, to, before), PAGE, locale);
  }

  @GetMapping(value = "/admin/v1/audit/export", produces = "text/csv")
  String export(
      @AuthenticationPrincipal Jwt token,
      @RequestParam(required = false) AuditSearch.Group group,
      @RequestParam(required = false) @Size(max = 80) String q,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Locale locale) {
    access.require(token, AREA, Level.VIEW);
    var csv = new StringBuilder("id,at,actor,roles,action,on,reason,outcome\n");
    for (var e : entries(query(group, q, from, to, null), EXPORT, locale)) {
      csv.append(
              String.join(
                  ",",
                  String.valueOf(e.id()),
                  e.at().toString(),
                  cell(e.actor().name()),
                  cell(String.join(" ", e.actor().roles())),
                  cell(e.action()),
                  cell(e.target().label()),
                  cell(e.reason()),
                  e.outcome()))
          .append('\n');
    }
    audit.adminAction(
        "audit.export",
        "audit-log",
        "search",
        group == null ? Map.of() : Map.of("group", group.name()));
    return csv.toString();
  }

  private AuditSearch.Query query(
      AuditSearch.Group group, String q, Instant from, Instant to, Long before) {
    List<String> named = q == null || q.isBlank() ? List.of() : staff.idsNamed(q.trim());
    return new AuditSearch.Query(group, q, named, from, to, before);
  }

  private List<Entry> entries(AuditSearch.Query query, int limit, Locale locale) {
    var rows = search.search(query, limit);
    Set<String> staffIds = new HashSet<>();
    for (var r : rows) {
      if (r.actorType().equals("STAFF")) {
        staffIds.add(r.actorId());
      }
      if ("staff".equals(r.targetType()) && r.targetId() != null) {
        staffIds.add(r.targetId());
      }
    }
    var names = staff.names(staffIds);
    Map<String, String> people = new HashMap<>();
    return rows.stream()
        .map(
            r ->
                new Entry(
                    r.id(),
                    r.at(),
                    new Actor(r.actorType(), actorName(r, names, people), r.actorRoles()),
                    r.action(),
                    new Target(r.targetType(), r.targetId(), label(r, names, people, locale)),
                    r.details().isEmpty() ? null : r.details(),
                    r.reason(),
                    r.outcome()))
        .toList();
  }

  private String actorName(
      AuditSearch.Row r, Map<String, String> names, Map<String, String> people) {
    return switch (r.actorType()) {
      case "STAFF" -> names.getOrDefault(r.actorId(), r.actorId());
      case "SEEKER", "SPECIALIST", "GUEST" -> person(r.actorId(), people);
      case "SYSTEM" -> "Solace";
      default -> r.actorId();
    };
  }

  private String label(
      AuditSearch.Row r, Map<String, String> names, Map<String, String> people, Locale locale) {
    String id = r.targetId();
    if (id == null) {
      return r.subjectId() != null ? person(r.subjectId(), people) : r.targetType();
    }
    return switch (r.targetType()) {
      case "booking" -> "BK-" + shortId(id);
      case "staff" -> names.getOrDefault(id, id);
      case "specialist", "specialist-application" ->
          uuid(id)
              .flatMap(u -> specialists.summary(u, locale))
              .map(SpecialistOffers.Summary::fullName)
              .orElse(shortId(id));
      case "setting", "flag" -> id;
      default -> r.subjectId() != null ? person(r.subjectId(), people) : shortId(id);
    };
  }

  /** Seeker or guest reference, never a name (D41). */
  private String person(String subjectId, Map<String, String> people) {
    return people.computeIfAbsent(
        subjectId,
        s ->
            uuid(s)
                .map(u -> BookingRecords.personRef(u, accounts.appLanguage(u).isEmpty()))
                .orElse(s));
  }

  private static String shortId(String id) {
    String hex = id.replace("-", "");
    return (hex.length() > 8 ? hex.substring(0, 8) : hex).toUpperCase(Locale.ROOT);
  }

  private static Optional<UUID> uuid(String s) {
    try {
      return Optional.of(UUID.fromString(s));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
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
