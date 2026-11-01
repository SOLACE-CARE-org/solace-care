package care.solace.core.identity;

import care.solace.core.admin.TestCodes;
import care.solace.core.notifications.SignInCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/v1/sign-in-codes} (contracts/openapi/core-internal.yaml): Keycloak's code
 * authenticator hands Core a code to send. Only Keycloak's sender client has the scope; the gateway
 * never routes {@code /internal}.
 */
@RestController
class SignInCodeController {

  private final SignInCodes codes;
  private final AccountStore store;
  private final TestCodes testCodes;

  SignInCodeController(SignInCodes codes, AccountStore store, TestCodes testCodes) {
    this.codes = codes;
    this.store = store;
    this.testCodes = testCodes;
  }

  record TestCodeState(boolean signIn, boolean staff) {}

  /** Whether Keycloak may accept the default test codes (D63). */
  @GetMapping("/internal/v1/test-codes")
  @PreAuthorize("hasAuthority('SCOPE_core:sign-in-codes.send')")
  TestCodeState testCodes() {
    return new TestCodeState(testCodes.signInOn(), testCodes.staffOn());
  }

  record SignInCode(
      @NotNull SignInCodes.Channel channel,
      @NotNull @Size(max = 254) String to,
      @NotNull @Pattern(regexp = "^[0-9]{4,10}$") String code,
      @NotNull @Min(1) Integer expiresInMinutes,
      @Pattern(regexp = "en|fr") String locale) {}

  @PostMapping("/internal/v1/sign-in-codes")
  @PreAuthorize("hasAuthority('SCOPE_core:sign-in-codes.send')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void send(@Valid @RequestBody SignInCode body) {
    codes.send(body.channel(), body.to(), body.code(), body.expiresInMinutes(), language(body));
  }

  /** The account's app language when the contact has an account, else Keycloak's, else English. */
  private Locale language(SignInCode body) {
    Optional<AccountStore.Row> account =
        body.channel() == SignInCodes.Channel.SMS
            ? store.byPhone(body.to())
            : store.byEmail(body.to().toLowerCase(Locale.ROOT));
    return account
        .map(AccountStore.Row::locale)
        .orElseGet(() -> Locale.of(body.locale() == null ? "en" : body.locale()));
  }
}
