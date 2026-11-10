package care.solace.tracking.context;

import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.tracking.Seekers;
import care.solace.tracking.consent.TrackingConsent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's context questions and answers (tag modules; D61). Answers can only switch rules
 * off, a skipped question switches nothing, and every read is audited.
 */
@RestController
class ContextController {

  static final String TRYING_TO_CONCEIVE = "trying_to_conceive";
  static final String HORMONAL_TREATMENT = "hormonal_treatment";

  private final Seekers seekers;
  private final TrackingConsent consent;
  private final ContextQuestions questions;
  private final ContextAnswers answers;
  private final AuditLog audit;

  ContextController(
      Seekers seekers,
      TrackingConsent consent,
      ContextQuestions questions,
      ContextAnswers answers,
      AuditLog audit) {
    this.seekers = seekers;
    this.consent = consent;
    this.questions = questions;
    this.answers = answers;
    this.audit = audit;
  }

  record Answer(@NotNull JsonNode value) {}

  /** The old profile shape, kept while app builds that use it are still about (deprecated). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Profile(
      @jakarta.validation.constraints.Pattern(regexp = "DIAGNOSED|NOT_DIAGNOSED|NOT_SURE")
          String pcosStatus,
      Boolean tryingToConceive,
      @Size(max = 10) List<@NotBlank @Size(max = 40) String> treatments) {}

  @GetMapping("/v1/tracking/context-questions")
  List<QuestionView> questions(@AuthenticationPrincipal Jwt token) {
    seekers.require(token);
    return questions.all().stream().map(QuestionView::of).toList();
  }

  @GetMapping("/v1/tracking/context-answers")
  Map<String, JsonNode> answers(@AuthenticationPrincipal Jwt token) {
    UUID user = seekers.require(token);
    audit.read("tracking.context.read", "context-answers", null, user.toString());
    return answers.answers(user);
  }

  @PutMapping("/v1/tracking/context-answers/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void answer(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody Answer request) {
    UUID user = seekers.require(token);
    consent.require(user);
    var q = questions.find(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!q.accepts(request.value())) {
      throw new SolaceException(CommonError.REQUEST_INVALID);
    }
    answers.save(user, code, request.value());
  }

  /** Skipping a question again: the answer is deleted. */
  @DeleteMapping("/v1/tracking/context-answers/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void skip(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    UUID user = seekers.require(token);
    answers.clear(user, code);
  }

  /** Deprecated: the old profile, read from the context answers. PCOS status is no longer kept. */
  @GetMapping("/v1/tracking/profile")
  Profile profile(@AuthenticationPrincipal Jwt token) {
    UUID user = seekers.require(token);
    audit.read("tracking.context.read", "context-answers", null, user.toString());
    return toProfile(answers.answers(user));
  }

  /** Deprecated: saves the old profile as context answers; a missing value skips the question. */
  @PutMapping("/v1/tracking/profile")
  @Transactional
  Profile saveProfile(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Profile p) {
    UUID user = seekers.require(token);
    consent.require(user);
    if (p.tryingToConceive() == null) {
      answers.clear(user, TRYING_TO_CONCEIVE);
    } else {
      answers.save(user, TRYING_TO_CONCEIVE, BooleanNode.valueOf(p.tryingToConceive()));
    }
    boolean hormonal = p.treatments() != null && p.treatments().contains("hormonal");
    answers.save(user, HORMONAL_TREATMENT, BooleanNode.valueOf(hormonal));
    return toProfile(answers.answers(user));
  }

  static Profile toProfile(Map<String, JsonNode> a) {
    var trying = a.get(TRYING_TO_CONCEIVE);
    var hormonal = a.get(HORMONAL_TREATMENT);
    return new Profile(
        null,
        trying == null ? null : trying.asBoolean(),
        hormonal != null && hormonal.asBoolean() ? List.of("hormonal") : List.of());
  }
}
