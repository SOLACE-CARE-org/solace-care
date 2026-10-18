package care.solace.tracking.modules;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.Seekers;
import care.solace.tracking.TrackingError;
import care.solace.tracking.consent.TrackingConsent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module catalogue, enrolment and the person's tracking profile (tag modules; D51). Seekers
 * choose modules themselves; a module's minimum age is the only filter, read from Core at
 * enrolment.
 */
@RestController
class SeekerModulesController {

  private final Seekers seekers;
  private final TrackingConsent consent;
  private final ModuleStore store;
  private final Modules modules;
  private final AgeCheck age;
  private final JdbcClient db;
  private final Clock clock;

  SeekerModulesController(
      Seekers seekers,
      TrackingConsent consent,
      ModuleStore store,
      Modules modules,
      AgeCheck age,
      JdbcClient db,
      Clock clock) {
    this.seekers = seekers;
    this.consent = consent;
    this.store = store;
    this.modules = modules;
    this.age = age;
    this.db = db;
    this.clock = clock;
  }

  record Text(String en, String fr) {}

  record BindingView(String code, String version, boolean required, int position) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CatalogueModule(
      String code,
      String version,
      String accentColour,
      Text name,
      Text description,
      Text audience,
      Text imageAlt,
      UUID imageId,
      Integer minAge,
      JsonNode enrolmentQuestion,
      LocalTime reminderDefault,
      List<BindingView> elements,
      String minAppVersion,
      boolean enrolled,
      LocalTime reminderTime) {}

  record Enrol(@Size(max = 40) String answer, LocalTime reminderTime) {}

  record Reminder(LocalTime reminderTime) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Profile(
      @jakarta.validation.constraints.Pattern(regexp = "DIAGNOSED|NOT_DIAGNOSED|NOT_SURE")
          String pcosStatus,
      Boolean tryingToConceive,
      @Size(max = 10) List<@NotBlank @Size(max = 40) String> treatments) {}

  @GetMapping("/v1/tracking/modules")
  List<CatalogueModule> catalogue(@AuthenticationPrincipal Jwt token) {
    UUID user = seekers.require(token);
    Map<UUID, Object[]> enrolled =
        db
            .sql(
                "SELECT module_id, reminder_time FROM enrolment WHERE user_id = :u AND left_at IS NULL")
            .param("u", user)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getObject("module_id", UUID.class), rs.getTime("reminder_time")
                    })
            .list()
            .stream()
            .collect(Collectors.toMap(r -> (UUID) r[0], r -> r));
    return store.allPublished().stream()
        .map(
            v -> {
              var module = store.module(v.moduleId()).orElseThrow();
              var bound = modules.bound(v.id());
              String minApp =
                  bound.values().stream()
                      .map(e -> e.minAppVersion())
                      .max(SeekerModulesController::compareVersions)
                      .orElse("1.0.0");
              var e = enrolled.get(v.moduleId());
              Time reminder = e == null ? null : (Time) e[1];
              var d = v.details();
              return new CatalogueModule(
                  module.code(),
                  v.number(),
                  module.accentColour(),
                  new Text(d.nameEn(), d.nameFr()),
                  new Text(d.descriptionEn(), d.descriptionFr()),
                  new Text(d.audienceEn(), d.audienceFr()),
                  new Text(d.imageAltEn(), d.imageAltFr()),
                  d.imageAssetId(),
                  d.minAge(),
                  d.enrolmentQuestion(),
                  d.reminderDefault(),
                  store.bindings(v.id()).stream()
                      .map(
                          b -> {
                            var el =
                                bound.values().stream()
                                    .filter(x -> x.id().equals(b.elementId()))
                                    .findFirst()
                                    .orElseThrow();
                            return new BindingView(
                                el.code(), el.version(), b.required(), b.position());
                          })
                      .toList(),
                  minApp,
                  e != null,
                  reminder == null ? null : reminder.toLocalTime());
            })
        .toList();
  }

  /** Enrols the seeker in the module's published version; the answer stays in Tracking. */
  @PostMapping("/v1/tracking/modules/{code}/enrolment")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void enrol(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody Enrol request) {
    UUID user = seekers.require(token);
    consent.require(user);
    var module =
        store.moduleByCode(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    var version =
        store.published(module.id()).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (version.details().minAge() != null) {
      int years =
          age.years(user).orElseThrow(() -> new SolaceException(TrackingError.NOT_ELIGIBLE));
      if (years < version.details().minAge()) {
        throw new SolaceException(TrackingError.NOT_ELIGIBLE);
      }
    }
    var question = version.details().enrolmentQuestion();
    if (request.answer() != null && question != null && question.path("options").isArray()) {
      boolean known = false;
      for (JsonNode o : question.path("options")) {
        known |= o.path("code").asText().equals(request.answer());
      }
      if (!known) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    LocalTime reminder =
        request.reminderTime() != null
            ? request.reminderTime()
            : version.details().reminderDefault();
    db.sql(
            """
            INSERT INTO enrolment (user_id, module_id, module_version_id, enrolled_at, reminder_time, enrolment_answer)
            VALUES (:u, :m, :v, :now, :r, :a)
            ON CONFLICT (user_id, module_id) DO UPDATE
               SET module_version_id = EXCLUDED.module_version_id, enrolled_at = EXCLUDED.enrolled_at,
                   left_at = NULL, reminder_time = EXCLUDED.reminder_time,
                   enrolment_answer = EXCLUDED.enrolment_answer
            """)
        .param("u", user)
        .param("m", module.id())
        .param("v", version.id())
        .param("now", Timestamp.from(clock.instant()))
        .param("r", reminder == null ? null : Time.valueOf(reminder))
        .param("a", request.answer())
        .update();
  }

  /** Leaving keeps everything logged: observations belong to elements, not modules. */
  @DeleteMapping("/v1/tracking/modules/{code}/enrolment")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void leave(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    UUID user = seekers.require(token);
    var module =
        store.moduleByCode(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    db.sql(
            "UPDATE enrolment SET left_at = :now WHERE user_id = :u AND module_id = :m AND left_at IS NULL")
        .param("u", user)
        .param("m", module.id())
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  @PutMapping("/v1/tracking/modules/{code}/reminder")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reminder(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @RequestBody Reminder request) {
    UUID user = seekers.require(token);
    var module =
        store.moduleByCode(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    int updated =
        db.sql(
                "UPDATE enrolment SET reminder_time = :r WHERE user_id = :u AND module_id = :m AND left_at IS NULL")
            .param("u", user)
            .param("m", module.id())
            .param(
                "r", request.reminderTime() == null ? null : Time.valueOf(request.reminderTime()))
            .update();
    if (updated == 0) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  @GetMapping("/v1/tracking/profile")
  Profile profile(@AuthenticationPrincipal Jwt token) {
    UUID user = seekers.require(token);
    return db.sql("SELECT * FROM tracking_profile WHERE user_id = :u")
        .param("u", user)
        .query(
            (rs, n) ->
                new Profile(
                    rs.getString("pcos_status"),
                    (Boolean) rs.getObject("trying_to_conceive"),
                    List.of((String[]) rs.getArray("treatments").getArray())))
        .optional()
        .orElse(new Profile(null, null, List.of()));
  }

  /** Profile answers can only switch rules off (Tracking Service Spec, What a rule is made of). */
  @PutMapping("/v1/tracking/profile")
  Profile saveProfile(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Profile p) {
    UUID user = seekers.require(token);
    db.sql(
            """
            INSERT INTO tracking_profile (user_id, pcos_status, trying_to_conceive, treatments, updated_at)
            VALUES (:u, :p, :t, :tr, :now)
            ON CONFLICT (user_id) DO UPDATE SET pcos_status = EXCLUDED.pcos_status,
                   trying_to_conceive = EXCLUDED.trying_to_conceive, treatments = EXCLUDED.treatments,
                   updated_at = EXCLUDED.updated_at
            """)
        .param("u", user)
        .param("p", p.pcosStatus())
        .param("t", p.tryingToConceive())
        .param(
            "tr",
            (p.treatments() == null ? List.<String>of() : p.treatments()).toArray(String[]::new))
        .param("now", Timestamp.from(clock.instant()))
        .update();
    return profile(token);
  }

  /** "1.2.0" against "1.10.0", by number. */
  static int compareVersions(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int p = i < x.length ? Integer.parseInt(x[i]) : 0;
      int q = i < y.length ? Integer.parseInt(y[i]) : 0;
      if (p != q) {
        return Integer.compare(p, q);
      }
    }
    return 0;
  }
}
