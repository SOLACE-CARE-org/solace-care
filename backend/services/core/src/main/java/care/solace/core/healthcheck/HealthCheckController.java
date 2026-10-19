package care.solace.core.healthcheck;

import care.solace.core.catalog.Catalogue;
import care.solace.core.healthcheck.Definition.Question;
import care.solace.core.healthcheck.Definition.Rule;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The health check for seekers and guests (tag health-check; D55). Works without an account: a
 * guest's response is found by its token, a seeker's by their account.
 */
@RestController
class HealthCheckController {

  static final String TOKEN = "X-Health-Check-Token";
  static final int MATCHES = 10;

  private final Versions versions;
  private final Responses responses;
  private final Accounts accounts;
  private final Catalogue catalogue;
  private final SpecialistOffers specialists;
  private final MessageSource messages;
  private final care.solace.core.admin.PlatformSettings settings;

  HealthCheckController(
      Versions versions,
      Responses responses,
      Accounts accounts,
      Catalogue catalogue,
      SpecialistOffers specialists,
      MessageSource messages,
      care.solace.core.admin.PlatformSettings settings) {
    this.settings = settings;
    this.versions = versions;
    this.responses = responses;
    this.accounts = accounts;
    this.catalogue = catalogue;
    this.specialists = specialists;
    this.messages = messages;
  }

  // --- Shapes (contracts: HealthCheckForm, HealthCheckResult, HealthCheckMatch) ------------------

  record FormOption(String code, String label) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record FormQuestion(
      String code,
      Definition.Kind kind,
      boolean multi,
      int step,
      String text,
      String intro,
      String source,
      List<FormOption> options,
      Definition.ShowIf showIf) {}

  record Form(String version, List<FormQuestion> questions, List<Long> priceBands) {}

  record Start(@NotBlank @Size(max = 20) String consentVersion) {}

  record Answers(@NotNull Map<String, @Size(max = 20) List<@Size(max = 40) String>> answers) {}

  record Preferences(
      @Size(max = 10) String sessionLanguage,
      @Pattern(regexp = "AUDIO|VIDEO") String modality,
      @Min(0) Long maxPrice,
      @Pattern(regexp = "WOMAN|MAN") String gender) {}

  record Callback(@NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone) {}

  record Specialty(String code, String label, String person) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Result(
      UUID responseId,
      Specialty specialty,
      Specialty alsoFit,
      List<String> reasons,
      String alsoFitReason,
      Instant completedAt,
      boolean stale,
      Preferences preferences) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Match(
      UUID id,
      String fullName,
      String professionLabel,
      List<String> languages,
      String specialty,
      boolean alsoFit,
      List<String> focusAreas,
      Instant nextAvailable,
      Long price) {}

  // --- Endpoints --------------------------------------------------------------------------------

  @GetMapping("/v1/health-check")
  Form form(Locale locale) {
    Versions.Version v =
        versions.published().orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    boolean fr = french(locale);
    return new Form(
        v.number(),
        v.questions().stream()
            .map(
                q ->
                    new FormQuestion(
                        q.code(),
                        q.kind(),
                        q.multi(),
                        q.step(),
                        fr ? q.textFr() : q.textEn(),
                        fr ? q.introFr() : q.introEn(),
                        q.screener() == null
                            ? null
                            : Canon.PHQ9.equals(q.screener()) ? "PHQ-9" : "GAD-7",
                        q.options().stream()
                            .map(o -> new FormOption(o.code(), fr ? o.labelFr() : o.labelEn()))
                            .toList(),
                        q.showIf()))
            .toList(),
        List.of(
            settings.number("solace.health-check.price-band-1", 15000),
            settings.number("solace.health-check.price-band-2", 25000)));
  }

  @PostMapping("/v1/health-check/responses")
  @ResponseStatus(HttpStatus.CREATED)
  Responses.Started start(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Start body) {
    return responses.start(seeker(token), body.consentVersion());
  }

  @GetMapping("/v1/health-check/responses/latest")
  Result latest(@AuthenticationPrincipal Jwt token, Locale locale) {
    UUID account = seeker(token).orElseThrow(() -> new SolaceException(CommonError.AUTH_REQUIRED));
    var r = responses.latest(account).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return result(r, locale);
  }

  @PutMapping("/v1/health-check/responses/{id}/answers")
  Responses.Progress answer(
      @AuthenticationPrincipal Jwt token,
      @RequestHeader(value = TOKEN, required = false) String guest,
      @PathVariable UUID id,
      @Valid @RequestBody Answers body,
      Locale locale) {
    var r = responses.require(id, seeker(token), guest);
    return responses.answer(r, body.answers(), french(locale) ? "fr" : "en");
  }

  @PostMapping("/v1/health-check/responses/{id}/completion")
  Result complete(
      @AuthenticationPrincipal Jwt token,
      @RequestHeader(value = TOKEN, required = false) String guest,
      @PathVariable UUID id,
      @Valid @RequestBody Preferences body,
      Locale locale) {
    if (body.sessionLanguage() != null
        && !catalogue.areSessionLanguages(List.of(body.sessionLanguage()))) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    var r = responses.require(id, seeker(token), guest);
    var done =
        responses.complete(
            r,
            new Responses.Preferences(
                body.sessionLanguage(), body.modality(), body.maxPrice(), body.gender()));
    return result(done, locale);
  }

  @GetMapping("/v1/health-check/responses/{id}/matches")
  List<Match> matches(
      @AuthenticationPrincipal Jwt token,
      @RequestHeader(value = TOKEN, required = false) String guest,
      @PathVariable UUID id,
      Locale locale) {
    var r = responses.require(id, seeker(token), guest);
    if (!r.status().equals("COMPLETED")) {
      throw new SolaceException(HealthCheckError.NOT_COMPLETE);
    }
    if (responses.stale(r)) {
      throw new SolaceException(HealthCheckError.RETAKE_NEEDED);
    }
    return rank(r, locale);
  }

  @PostMapping("/v1/health-check/responses/{id}/callback")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void callback(
      @AuthenticationPrincipal Jwt token,
      @RequestHeader(value = TOKEN, required = false) String guest,
      @PathVariable UUID id,
      @Valid @RequestBody Callback body,
      Locale locale) {
    var r = responses.require(id, seeker(token), guest);
    responses.callback(r, body.phone(), french(locale) ? "fr" : "en");
  }

  @PostMapping("/v1/health-check/responses/{id}/claim")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void claim(
      @AuthenticationPrincipal Jwt token,
      @RequestHeader(value = TOKEN, required = false) String guest,
      @PathVariable UUID id) {
    UUID account = seeker(token).orElseThrow(() -> new SolaceException(CommonError.AUTH_REQUIRED));
    if (guest == null) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    var r = responses.require(id, Optional.empty(), guest);
    responses.claim(r, account);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** A seeker's account id, when the caller is a signed-in seeker; guests have none. */
  private Optional<UUID> seeker(Jwt token) {
    if (token == null) {
      return Optional.empty();
    }
    Account a = accounts.require(token);
    if (a.type() != Account.Type.SEEKER) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return Optional.of(a.id());
  }

  Result result(Responses.Response r, Locale locale) {
    Versions.Version v = versions.require(r.versionId());
    var state = r.state();
    boolean fr = french(locale);
    Map<String, Rule> rules =
        v.rules().stream().collect(Collectors.toMap(Rule::code, x -> x, (a, b) -> a));
    List<String> reasons =
        state.reasons() == null
            ? List.of()
            : state.reasons().stream()
                .map(rules::get)
                .filter(java.util.Objects::nonNull)
                .map(x -> fr ? x.reasonFr() : x.reasonEn())
                .toList();
    if (reasons.isEmpty()) {
      reasons = List.of(messages.getMessage("health-check.no-reasons", null, locale));
    }
    String alsoFitReason =
        state.alsoFitReasons() == null || state.alsoFitReasons().isEmpty()
            ? null
            : Optional.ofNullable(rules.get(state.alsoFitReasons().get(0)))
                .map(x -> fr ? x.reasonFr() : x.reasonEn())
                .orElse(null);
    var p = r.preferences();
    return new Result(
        r.id(),
        specialty(state.specialty(), locale),
        state.alsoFit() == null ? null : specialty(state.alsoFit(), locale),
        reasons,
        alsoFitReason,
        r.completedAt(),
        responses.stale(r),
        p == null
            ? null
            : new Preferences(p.sessionLanguage(), p.modality(), p.maxPrice(), p.gender()));
  }

  private Specialty specialty(String code, Locale locale) {
    if (code == null) {
      return null;
    }
    String label =
        catalogue.specialties(locale).stream()
            .filter(e -> e.code().equals(code))
            .map(Catalogue.Entry::label)
            .findFirst()
            .orElse(code);
    return new Specialty(code, label, catalogue.professionFor(code, locale).orElse(label));
  }

  /**
   * Specialists of the suggested specialty (then the "also a good fit" one) in the chosen language,
   * format and price band, ranked by overlap with the seeker's topics, then the soonest slot, then
   * rating (System Design, Health check, Matching). Gender is kept but cannot filter until
   * specialists record it (D55).
   */
  private List<Match> rank(Responses.Response r, Locale locale) {
    Versions.Version v = versions.require(r.versionId());
    var p = r.preferences();
    List<String> languages =
        p != null && p.sessionLanguage() != null
            ? List.of(p.sessionLanguage())
            : catalogue.sessionLanguages(locale).stream().map(Catalogue.Entry::code).toList();
    Set<String> topics = new HashSet<>();
    for (Question q : v.questions()) {
      for (String chosen : r.state().answers().getOrDefault(q.code(), List.of())) {
        q.options().stream()
            .filter(o -> o.code().equals(chosen) && o.topic() != null)
            .forEach(o -> topics.add(o.topic()));
      }
    }
    Map<String, String> topicLabels =
        catalogue.helpTopics(locale).stream()
            .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label, (a, b) -> a));
    List<String> wanted = new ArrayList<>();
    if (r.state().specialty() != null) {
      wanted.add(r.state().specialty());
    }
    if (r.state().alsoFit() != null) {
      wanted.add(r.state().alsoFit());
    }
    var found =
        specialists.candidates(
            wanted,
            languages,
            p == null ? null : p.modality(),
            p == null ? null : p.maxPrice(),
            locale,
            MATCHES);
    Comparator<SpecialistOffers.Candidate> order =
        Comparator.<SpecialistOffers.Candidate>comparingInt(
                c -> c.specialty().equals(r.state().specialty()) ? 0 : 1)
            .thenComparing(c -> -c.focusCodes().stream().filter(topics::contains).count())
            .thenComparing(
                SpecialistOffers.Candidate::nextAvailable,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> -c.rating());
    return found.stream()
        .sorted(order)
        .limit(MATCHES)
        .map(
            c ->
                new Match(
                    c.id(),
                    c.fullName(),
                    c.professionLabel(),
                    c.languages(),
                    c.specialty(),
                    !c.specialty().equals(r.state().specialty()),
                    c.focusCodes().stream()
                        .filter(topics::contains)
                        .map(t -> topicLabels.getOrDefault(t, t))
                        .toList(),
                    c.nextAvailable(),
                    c.price() > 0 ? c.price() : null))
        .toList();
  }

  private static boolean french(Locale locale) {
    return locale != null && "fr".equals(locale.getLanguage());
  }
}
