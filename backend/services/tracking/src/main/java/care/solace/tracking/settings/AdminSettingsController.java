package care.solace.tracking.settings;

import care.solace.audit.AuditLog;
import care.solace.tracking.modules.StaffGate;
import care.solace.tracking.wording.WordList;
import care.solace.tracking.wording.WordingRules;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The word list and Tracking settings for staff (tag admin-wording; provisional screens; D61).
 * Clinical reviewers maintain the word list; Operations and Super admins change settings with a
 * reason. Every change is audited.
 */
@RestController
class AdminSettingsController {

  private final StaffGate gate;
  private final WordingRules wording;
  private final TrackingSettings settings;
  private final AuditLog audit;

  AdminSettingsController(
      StaffGate gate, WordingRules wording, TrackingSettings settings, AuditLog audit) {
    this.gate = gate;
    this.wording = wording;
    this.settings = settings;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record WordingRuleView(
      UUID id,
      String language,
      String pattern,
      WordList.Kind kind,
      String createdBy,
      Instant createdAt) {}

  record WordingRuleRequest(
      @NotBlank @Pattern(regexp = "^(en|fr)$") String language,
      @NotBlank @Size(min = 2, max = 60) String pattern,
      @NotNull WordList.Kind kind) {}

  record CheckRequest(
      @NotNull @Size(max = 1000) String text,
      @NotBlank @Pattern(regexp = "^(en|fr)$") String language) {}

  record CheckResult(boolean allowed, List<String> blockedBy) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SettingView(
      String key,
      int value,
      int defaultValue,
      int min,
      int max,
      String unit,
      String updatedBy,
      Instant updatedAt) {}

  record ChangeRequest(@NotNull Integer value, @NotBlank @Size(max = 500) String reason) {}

  @GetMapping("/admin/v1/tracking/wording-rules")
  List<WordingRuleView> wordingRules(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return wording.all().stream().map(AdminSettingsController::view).toList();
  }

  @PostMapping("/admin/v1/tracking/wording-rules")
  @ResponseStatus(HttpStatus.CREATED)
  WordingRuleView addWordingRule(
      @AuthenticationPrincipal Jwt token, @Valid @RequestBody WordingRuleRequest r) {
    var staff = gate.require(token, true);
    var rule = wording.add(r.language(), r.pattern(), r.kind(), staff);
    audit.adminAction(
        "tracking.wording.add",
        "wording-rule",
        rule.id().toString(),
        Map.of("language", rule.language(), "kind", rule.kind().name()));
    return view(rule);
  }

  @DeleteMapping("/admin/v1/tracking/wording-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteWordingRule(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
    var staff = gate.require(token, true);
    var rule = wording.delete(id, staff);
    audit.adminAction(
        "tracking.wording.delete",
        "wording-rule",
        id.toString(),
        Map.of("language", rule.language(), "kind", rule.kind().name()));
  }

  @PostMapping("/admin/v1/tracking/wording-check")
  CheckResult check(@AuthenticationPrincipal Jwt token, @Valid @RequestBody CheckRequest r) {
    gate.require(token, false);
    var blocked = wording.current().blockedBy(r.text(), r.language());
    return new CheckResult(blocked.isEmpty(), blocked);
  }

  @GetMapping("/admin/v1/tracking/settings")
  List<SettingView> settings(@AuthenticationPrincipal Jwt token) {
    gate.require(token, false);
    return settings.all().stream().map(AdminSettingsController::view).toList();
  }

  @PutMapping("/admin/v1/tracking/settings/{key}")
  SettingView change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String key,
      @Valid @RequestBody ChangeRequest r) {
    var staff = gate.require(token, true);
    int before = settings.get(key);
    var after = settings.change(key, r.value(), staff);
    audit.adminAction(
        "tracking.setting.change",
        "tracking-setting",
        key,
        r.reason().strip(),
        Map.of("from", String.valueOf(before), "to", String.valueOf(after.value())));
    return view(after);
  }

  private static WordingRuleView view(WordingRules.Rule r) {
    return new WordingRuleView(
        r.id(), r.language(), r.pattern(), r.kind(), r.createdByName(), r.createdAt());
  }

  private static SettingView view(TrackingSettings.Setting s) {
    var d = s.definition();
    return new SettingView(
        d.key(),
        s.value(),
        d.defaultValue(),
        d.min(),
        d.max(),
        d.unit(),
        s.updatedByName(),
        s.updatedAt());
  }
}
