package care.solace.core.privacy;

import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/me/consents} (contracts/openapi/core.yaml, tag privacy). */
@RestController
class ConsentController {

  private final Accounts accounts;
  private final Consents consents;

  ConsentController(Accounts accounts, Consents consents) {
    this.accounts = accounts;
    this.consents = consents;
  }

  record Choice(@NotNull Purpose purpose, @NotNull Boolean granted) {}

  record ConsentChoices(@NotNull @Size(min = 1, max = 10) List<@NotNull @Valid Choice> choices) {}

  record ConsentView(Purpose purpose, boolean granted, String version, Instant recordedAt) {}

  record ConsentList(List<ConsentView> consents) {}

  @GetMapping("/v1/me/consents")
  ConsentList current(@AuthenticationPrincipal Jwt token) {
    return view(accounts.require(token));
  }

  @PutMapping("/v1/me/consents")
  ConsentList save(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ConsentChoices body) {
    Account account = accounts.require(token);
    Map<Purpose, Boolean> choices = new EnumMap<>(Purpose.class);
    for (Choice choice : body.choices()) {
      if (!consents.offered().contains(choice.purpose())
          || choices.put(choice.purpose(), choice.granted()) != null) {
        throw new SolaceException(CommonError.REQUEST_INVALID);
      }
    }
    consents.record(account, choices);
    return view(account);
  }

  private ConsentList view(Account account) {
    return new ConsentList(
        consents.current(account.id()).stream()
            .map(c -> new ConsentView(c.purpose(), c.granted(), c.version(), c.recordedAt()))
            .toList());
  }
}
