package care.solace.core.admin;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings and flags (admin screen 20; tag admin-settings) and the flags the apps read (tag
 * config). Super admins change them, Operations can look (permission matrix: settings).
 */
@RestController
class SettingsController {

  private static final String AREA = "settings";

  private final StaffAccess access;
  private final SettingCatalogue catalogue;
  private final Settings settings;
  private final AuditLog audit;

  SettingsController(
      StaffAccess access, SettingCatalogue catalogue, Settings settings, AuditLog audit) {
    this.access = access;
    this.catalogue = catalogue;
    this.settings = settings;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SettingView(
      String key,
      String group,
      String type,
      String value,
      String defaultValue,
      Long min,
      Long max,
      boolean editable,
      int version,
      String updatedBy,
      Instant updatedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record FlagView(
      String key,
      boolean enabled,
      boolean locked,
      int phase,
      String updatedBy,
      Instant updatedAt) {}

  record AllSettings(List<SettingView> settings, List<FlagView> flags) {}

  record AppConfig(Map<String, Boolean> flags) {}

  record Change(@NotBlank @Size(max = 40) String value, @NotBlank @Size(max = 500) String reason) {}

  record FlagChange(@NotNull Boolean enabled, @NotBlank @Size(max = 500) String reason) {}

  @GetMapping("/v1/config")
  ResponseEntity<AppConfig> config() {
    Map<String, Boolean> flags = new LinkedHashMap<>();
    for (var f : catalogue.flags()) {
      if (f.app()) {
        flags.put(f.key(), settings.enabled(f.key()));
      }
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
        .body(new AppConfig(flags));
  }

  @GetMapping("/admin/v1/settings")
  AllSettings all(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return new AllSettings(
        catalogue.settings().stream().map(this::view).toList(),
        catalogue.flags().stream().map(this::view).toList());
  }

  @PutMapping("/admin/v1/settings/{key}")
  SettingView change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String key,
      @Valid @RequestBody Change change) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var current = settings.change(key, change.value(), change.reason(), staff);
    audit.adminAction(
        "settings.change",
        "setting",
        key,
        change.reason().trim(),
        Map.of("version", String.valueOf(current.version())));
    return view(catalogue.setting(key).orElseThrow());
  }

  @GetMapping("/admin/v1/settings/{key}/history")
  List<Settings.Version> history(@AuthenticationPrincipal Jwt token, @PathVariable String key) {
    access.require(token, AREA, Level.VIEW);
    catalogue.setting(key).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    return settings.history(key);
  }

  @PutMapping("/admin/v1/flags/{key}")
  FlagView flag(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String key,
      @Valid @RequestBody FlagChange change) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var state = settings.changeFlag(key, change.enabled(), change.reason(), staff);
    audit.adminAction(
        "flags.change",
        "flag",
        key,
        change.reason().trim(),
        Map.of(
            "enabled",
            String.valueOf(state.enabled()),
            "version",
            String.valueOf(state.version())));
    return view(catalogue.flag(key).orElseThrow());
  }

  private SettingView view(SettingCatalogue.Definition d) {
    String fallback = catalogue.defaultValue(d);
    var current = settings.current(d.key());
    boolean ranged = d.type() != SettingCatalogue.Type.TIME;
    return new SettingView(
        d.key(),
        d.group().name(),
        d.type().name(),
        current.map(Settings.Current::value).orElse(fallback),
        fallback,
        ranged ? d.min() : null,
        ranged ? d.max() : null,
        d.editable(),
        current.map(Settings.Current::version).orElse(0),
        current.map(Settings.Current::updatedBy).orElse(null),
        current.map(Settings.Current::updatedAt).orElse(null));
  }

  private FlagView view(SettingCatalogue.Flag f) {
    var state = settings.flagState(f.key()).filter(s -> !f.locked());
    return new FlagView(
        f.key(),
        settings.enabled(f.key()),
        f.locked(),
        f.phase(),
        state.map(Settings.FlagState::updatedBy).orElse(null),
        state.map(Settings.FlagState::updatedAt).orElse(null));
  }
}
