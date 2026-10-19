package care.solace.core.safetyplan;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The seeker's safety plan (contracts/openapi/core.yaml, tag safety-plan; D57). */
@RestController
class SafetyPlanController {

  private final SafetyPlans plans;
  private final Accounts accounts;

  SafetyPlanController(SafetyPlans plans, Accounts accounts) {
    this.plans = plans;
    this.accounts = accounts;
  }

  @GetMapping("/v1/me/safety-plan")
  ResponseEntity<SafetyPlans.Plan> get(@AuthenticationPrincipal Jwt token, Locale locale) {
    return plans
        .find(seeker(token).id(), locale)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PutMapping("/v1/me/safety-plan")
  SafetyPlans.Plan save(
      @AuthenticationPrincipal Jwt token,
      @Valid @RequestBody SafetyPlans.Edit edit,
      Locale locale) {
    return plans.save(seeker(token).id(), edit, locale);
  }

  @DeleteMapping("/v1/me/safety-plan")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal Jwt token) {
    plans.delete(seeker(token).id());
  }

  private Account seeker(Jwt token) {
    Account me = accounts.require(token);
    if (me.type() != Account.Type.SEEKER) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return me;
  }
}
