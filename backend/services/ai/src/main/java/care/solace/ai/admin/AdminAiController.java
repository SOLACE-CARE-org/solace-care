package care.solace.ai.admin;

import care.solace.ai.features.Evaluations;
import care.solace.ai.features.FeatureError;
import care.solace.ai.features.Features;
import care.solace.ai.gateway.Feature;
import care.solace.ai.gateway.ModelGateway;
import care.solace.ai.safety.SafetyEvaluation;
import care.solace.audit.AuditLog;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin screen 26, AI controls (AIControls.dc.html; D56): each feature's model route, prompt and
 * policy versions and evaluations. A feature is switched on only while its live version's latest
 * evaluation passed in EN and FR; a new version goes live only with a passing evaluation and a
 * Clinical reviewer's sign-off. Every change is audited.
 */
@RestController
class AdminAiController {

  private final StaffGate gate;
  private final Features features;
  private final Evaluations evaluations;
  private final ModelGateway gateway;
  private final SafetyEvaluation safety;
  private final AuditLog audit;

  AdminAiController(
      StaffGate gate,
      Features features,
      Evaluations evaluations,
      ModelGateway gateway,
      SafetyEvaluation safety,
      AuditLog audit) {
    this.gate = gate;
    this.features = features;
    this.evaluations = evaluations;
    this.gateway = gateway;
    this.safety = safety;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record EvalSummary(
      UUID id,
      int passedEn,
      int totalEn,
      int passedFr,
      int totalFr,
      boolean passed,
      List<Evaluations.Failure> failures,
      Instant ranAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Version(
      UUID id,
      String version,
      String status,
      String system,
      String policy,
      String createdBy,
      Instant createdAt,
      String signedOffBy,
      Instant promotedAt,
      EvalSummary evaluation) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record FeatureView(
      String feature,
      boolean enabled,
      List<String> routes,
      Version live,
      Version candidate,
      String changedBy,
      Instant changedAt) {}

  record Safety(
      String classifier,
      String policy,
      double recallEn,
      double recallFr,
      double gate,
      boolean passing) {}

  record Overview(Safety safetyMonitor, List<FeatureView> features) {}

  record NewVersion(
      @NotBlank @Size(max = 8000) String system, @NotBlank @Size(max = 4000) String policy) {}

  record Switch(@NotNull Boolean enabled, @NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/admin/v1/ai/features")
  Overview overview(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    var s = safety.evaluate();
    return new Overview(
        new Safety(s.classifier(), s.policy(), s.recallEn(), s.recallFr(), s.gate(), s.passing()),
        features.all().stream().map(this::view).toList());
  }

  @GetMapping("/admin/v1/ai/features/{feature}/versions")
  List<Version> versions(@AuthenticationPrincipal Jwt token, @PathVariable String feature) {
    gate.require(token, false);
    return features.versions(feature(feature)).stream().map(this::version).toList();
  }

  @PostMapping("/admin/v1/ai/features/{feature}/versions")
  @ResponseStatus(HttpStatus.CREATED)
  Version draft(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String feature,
      @Valid @RequestBody NewVersion body) {
    var staff = gate.require(token, true);
    var v = features.draft(feature(feature), body.system(), body.policy(), staff.id());
    audit.adminAction(
        "ai.prompt.draft", "prompt-version", v.id().toString(), Map.of("feature", feature));
    return version(v);
  }

  @PostMapping("/admin/v1/ai/versions/{versionId}/evaluation")
  Version evaluate(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, true);
    var v = features.require(versionId);
    var run = evaluations.evaluate(v, staff.id());
    audit.adminAction(
        "ai.prompt.evaluate",
        "prompt-version",
        versionId.toString(),
        Map.of("passed", String.valueOf(run.passed())));
    return version(features.require(versionId));
  }

  /** A Clinical reviewer signs off the graded sample; view access is enough for this one step. */
  @PostMapping("/admin/v1/ai/versions/{versionId}/sign-off")
  Version signOff(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, false);
    if (!staff.clinicalReviewer()) {
      throw new SolaceException(FeatureError.SIGN_OFF_NEEDED);
    }
    var v = features.signOff(versionId, staff.name());
    audit.adminAction("ai.prompt.sign-off", "prompt-version", versionId.toString(), Map.of());
    return version(v);
  }

  @PostMapping("/admin/v1/ai/versions/{versionId}/promotion")
  Version promote(@AuthenticationPrincipal Jwt token, @PathVariable UUID versionId) {
    var staff = gate.require(token, true);
    var v = features.promote(versionId, staff.name());
    audit.adminAction("ai.prompt.promote", "prompt-version", versionId.toString(), Map.of());
    return version(v);
  }

  @PutMapping("/admin/v1/ai/features/{feature}/switch")
  FeatureView turn(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String feature,
      @Valid @RequestBody Switch body) {
    var staff = gate.require(token, true);
    var s = features.switchTo(feature(feature), body.enabled(), staff.name());
    audit.adminAction(
        body.enabled() ? "ai.feature.on" : "ai.feature.off",
        "ai-feature",
        feature,
        body.reason(),
        Map.of());
    return view(s);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private static Feature feature(String code) {
    try {
      return Feature.valueOf(code);
    } catch (IllegalArgumentException e) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
  }

  private FeatureView view(Features.State s) {
    var all = features.versions(s.feature());
    var live =
        all.stream()
            .filter(v -> v.status().equals("LIVE"))
            .findFirst()
            .map(this::version)
            .orElse(null);
    var candidate =
        all.stream()
            .filter(v -> v.status().equals("DRAFT"))
            .findFirst()
            .map(this::version)
            .orElse(null);
    return new FeatureView(
        s.feature().name(),
        s.enabled(),
        gateway.routeNames(s.feature()),
        live,
        candidate,
        s.changedBy(),
        s.changedAt());
  }

  private Version version(Features.PromptVersion v) {
    var run =
        evaluations
            .latest(v.id())
            .map(
                r ->
                    new EvalSummary(
                        r.id(),
                        r.passedEn(),
                        r.totalEn(),
                        r.passedFr(),
                        r.totalFr(),
                        r.passed(),
                        r.failures(),
                        r.ranAt()))
            .orElse(null);
    return new Version(
        v.id(),
        v.version(),
        v.status(),
        v.system(),
        v.policy(),
        v.createdBy(),
        v.createdAt(),
        v.signedOffBy(),
        v.promotedAt(),
        run);
  }
}
