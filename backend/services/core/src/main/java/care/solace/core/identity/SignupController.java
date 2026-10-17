package care.solace.core.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/signups} (contracts/openapi/core.yaml, startSignup). Open without a token. */
@RestController
class SignupController {

  private final Signups signups;

  SignupController(Signups signups) {
    this.signups = signups;
  }

  record SignupBody(
      @NotBlank @Size(max = 60) String displayName,
      @NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone,
      @Email @Size(max = 254) String email,
      @NotNull LocalDate dateOfBirth,
      @NotBlank @Size(max = 40) String termsVersion,
      @NotBlank @Size(max = 40) String privacyNoticeVersion,
      @NotNull @Pattern(regexp = "en|fr") String appLanguage) {}

  @PostMapping("/v1/signups")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void start(@Valid @RequestBody SignupBody body) {
    signups.start(
        new Signups.Request(
            body.displayName(),
            body.phone(),
            body.email(),
            body.dateOfBirth(),
            body.termsVersion(),
            body.privacyNoticeVersion(),
            Locale.of(body.appLanguage())));
  }
}
