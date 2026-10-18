package care.solace.core.notifications;

import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A staff member's browser subscribes to staff notifications (tag admin-push; D47). */
@RestController
class StaffPushController {

  private final StaffAccess access;
  private final WebPushStaff push;

  StaffPushController(StaffAccess access, WebPushStaff push) {
    this.access = access;
    this.push = push;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PushSetup(String publicKey, int subscriptions) {}

  record NewSubscription(
      @NotBlank @Pattern(regexp = "^https://.+") @Size(max = 1000) String endpoint,
      @NotBlank @Size(max = 200) String p256dh,
      @NotBlank @Size(max = 100) String auth,
      @NotBlank @Pattern(regexp = "en|fr") String language) {}

  /** Every staff role sees the overview, so this is any staff member. */
  @GetMapping("/admin/v1/me/push")
  PushSetup setup(@AuthenticationPrincipal Jwt token) {
    var staff = access.require(token, "overview", Level.VIEW);
    return new PushSetup(push.publicKey().orElse(null), push.subscriptions(staff.id()));
  }

  @PutMapping("/admin/v1/me/push-subscription")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void subscribe(@AuthenticationPrincipal Jwt token, @Valid @RequestBody NewSubscription s) {
    var staff = access.require(token, "overview", Level.VIEW);
    push.subscribe(
        new WebPushStaff.Subscription(
            s.endpoint(), staff.id(), s.p256dh(), s.auth(), s.language()));
  }

  @DeleteMapping("/admin/v1/me/push-subscription")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unsubscribe(@AuthenticationPrincipal Jwt token, @RequestParam String endpoint) {
    var staff = access.require(token, "overview", Level.VIEW);
    push.unsubscribe(staff.id(), endpoint);
  }
}
