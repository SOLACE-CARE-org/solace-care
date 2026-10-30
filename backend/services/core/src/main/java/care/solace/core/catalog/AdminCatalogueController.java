package care.solace.core.catalog;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specialties, professions, session languages and help topics for staff (admin screen 14; tag
 * admin-catalogue; D43). Every entry has both EN and FR, so nothing seekers see is published in one
 * language only. A specialty sits under at most one main specialty, and a profession lists the
 * specialties it allows (D62). A specialty change tells Tracking and AI through {@code
 * core.config.changed}.
 */
@RestController
class AdminCatalogueController {

  private static final String AREA = "catalogue";
  private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]*$");

  /** ISO 639, with an optional region or script (D16). */
  private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[a-z0-9]+)?$");

  /** The tables behind each kind; names never come from the request. */
  private enum Kind {
    SPECIALTIES("specialties", "specialty", "label_en", "label_fr"),
    PROFESSIONS("professions", "profession", "label_en", "label_fr"),
    SESSION_LANGUAGES("session-languages", "session_language", "name_en", "name_fr"),
    HELP_TOPICS("help-topics", "help_topic", "label_en", "label_fr");

    final String path;
    final String table;
    final String en;
    final String fr;

    Kind(String path, String table, String en, String fr) {
      this.path = path;
      this.table = table;
      this.en = en;
      this.fr = fr;
    }

    static Kind of(String path) {
      for (Kind k : values()) {
        if (k.path.equals(path)) {
          return k;
        }
      }
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Entry(
      String code,
      String labelEn,
      String labelFr,
      int position,
      boolean enabled,
      String parentCode,
      List<String> specialties) {}

  record AdminCatalogue(
      List<Entry> specialties,
      List<Entry> professions,
      List<Entry> sessionLanguages,
      List<Entry> helpTopics,
      List<Integer> durations) {}

  record Change(
      @NotBlank @Size(max = 80) String labelEn,
      @NotBlank @Size(max = 80) String labelFr,
      @NotNull Boolean enabled,
      @Min(1) Integer position,
      @Size(max = 40) String parentCode,
      @Size(min = 1, max = 50) List<@NotBlank String> specialties,
      @NotBlank @Size(max = 500) String reason) {}

  record ConfigChanged(String kind, String key, int configVersion) {}

  private final StaffAccess access;
  private final JdbcClient db;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;
  private final List<Integer> durations;
  private final care.solace.core.admin.PlatformSettings platform;

  AdminCatalogueController(
      StaffAccess access,
      JdbcClient db,
      AuditLog audit,
      ApplicationEventPublisher events,
      @Value("${solace.specialists.pricing.durations:30,50,80}") List<Integer> durations,
      care.solace.core.admin.PlatformSettings platform) {
    this.platform = platform;
    this.access = access;
    this.db = db;
    this.audit = audit;
    this.events = events;
    this.durations = durations;
  }

  @GetMapping("/admin/v1/catalogue")
  AdminCatalogue catalogue(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return new AdminCatalogue(
        entries(Kind.SPECIALTIES),
        entries(Kind.PROFESSIONS),
        entries(Kind.SESSION_LANGUAGES),
        entries(Kind.HELP_TOPICS),
        platform
            .numbers(
                "solace.specialists.pricing.durations",
                durations.stream().map(Integer::longValue).toList())
            .stream()
            .map(Long::intValue)
            .toList());
  }

  @PutMapping("/admin/v1/catalogue/{kind}/{code}")
  @Transactional
  Entry save(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String kind,
      @PathVariable String code,
      @Valid @RequestBody Change change) {
    access.require(token, AREA, Level.CHANGE);
    Kind k = Kind.of(kind);
    if (!(k == Kind.SESSION_LANGUAGES ? LANGUAGE : CODE).matcher(code).matches()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    String parent = blankToNull(change.parentCode());
    if ((parent != null && k != Kind.SPECIALTIES)
        || ((change.specialties() == null) != (k != Kind.PROFESSIONS))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    if (parent != null) {
      checkParent(code, parent);
    }
    List<String> specialties =
        change.specialties() == null
            ? List.of()
            : change.specialties().stream().map(String::strip).distinct().toList();
    if (k == Kind.PROFESSIONS
        && db.sql("SELECT count(*) FROM specialty WHERE code IN (:c)")
                .param("c", specialties)
                .query(Integer.class)
                .single()
            != specialties.size()) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    int position =
        change.position() != null
            ? change.position()
            : db.sql("SELECT coalesce(max(position), 0) + 1 FROM " + k.table)
                .query(Integer.class)
                .single();
    db.sql(
            "INSERT INTO "
                + k.table
                + " (code, "
                + k.en
                + ", "
                + k.fr
                + ", position, enabled) VALUES (:c, :en, :fr, :p, :e)"
                + " ON CONFLICT (code) DO UPDATE SET "
                + k.en
                + " = EXCLUDED."
                + k.en
                + ", "
                + k.fr
                + " = EXCLUDED."
                + k.fr
                + ", enabled = EXCLUDED.enabled"
                + (change.position() != null ? ", position = EXCLUDED.position" : ""))
        .param("c", code)
        .param("en", change.labelEn().trim())
        .param("fr", change.labelFr().trim())
        .param("p", position)
        .param("e", change.enabled())
        .update();
    if (k == Kind.SPECIALTIES) {
      db.sql("UPDATE specialty SET parent_code = :p WHERE code = :c")
          .param("c", code)
          .param("p", parent)
          .update();
    }
    if (k == Kind.PROFESSIONS) {
      // New choices only: specialists already approved for a specialty keep it (D62).
      db.sql(
              "DELETE FROM profession_specialty WHERE profession_code = :c"
                  + " AND specialty_code NOT IN (:s)")
          .param("c", code)
          .param("s", specialties)
          .update();
      for (String specialty : specialties) {
        db.sql(
                "INSERT INTO profession_specialty (profession_code, specialty_code)"
                    + " VALUES (:c, :s) ON CONFLICT DO NOTHING")
            .param("c", code)
            .param("s", specialty)
            .update();
      }
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("enabled", String.valueOf(change.enabled()));
    if (k == Kind.SPECIALTIES) {
      details.put("parent", parent == null ? "" : parent);
    }
    if (k == Kind.PROFESSIONS) {
      details.put("specialties", String.join(",", specialties));
    }
    audit.adminAction(
        "catalogue." + k.path + ".save", k.table, code, change.reason().trim(), details);
    if (k == Kind.SPECIALTIES) {
      events.publishEvent(
          Events.platformEnvelope(
              "core.config.changed", 1, new ConfigChanged("SPECIALTIES", code, 1)));
    }
    return entries(k).stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
  }

  /**
   * One level deep (D62): the parent is a main specialty, and a specialty with others under it
   * cannot move under one.
   */
  private void checkParent(String code, String parent) {
    boolean parentIsMain =
        db.sql("SELECT count(*) FROM specialty WHERE code = :p AND parent_code IS NULL")
                .param("p", parent)
                .query(Integer.class)
                .single()
            == 1;
    boolean hasChildren =
        db.sql("SELECT count(*) FROM specialty WHERE parent_code = :c")
                .param("c", code)
                .query(Integer.class)
                .single()
            > 0;
    if (parent.equals(code) || !parentIsMain || hasChildren) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
  }

  private List<Entry> entries(Kind k) {
    Map<String, List<String>> links = new HashMap<>();
    if (k == Kind.PROFESSIONS) {
      db.sql(
              """
              SELECT ps.profession_code, ps.specialty_code
                FROM profession_specialty ps JOIN specialty s ON s.code = ps.specialty_code
               ORDER BY s.position
              """)
          .query(
              rs -> {
                links.computeIfAbsent(rs.getString(1), c -> new ArrayList<>()).add(rs.getString(2));
              });
    }
    return db.sql(
            "SELECT code, "
                + k.en
                + " AS en, "
                + k.fr
                + " AS fr, position, enabled, "
                + (k == Kind.SPECIALTIES ? "parent_code" : "NULL AS parent_code")
                + " FROM "
                + k.table
                + " ORDER BY position, code")
        .query(
            (rs, n) ->
                new Entry(
                    rs.getString("code"),
                    rs.getString("en"),
                    rs.getString("fr"),
                    rs.getInt("position"),
                    rs.getBoolean("enabled"),
                    rs.getString("parent_code"),
                    k == Kind.PROFESSIONS
                        ? links.getOrDefault(rs.getString("code"), List.of())
                        : null))
        .list();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
