package care.solace.core.specialists;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/specialist-signups} (contracts/openapi/core.yaml, startSpecialistSignup). Open
 * without a token.
 */
@RestController
class SpecialistSignupController {

  private final Applications applications;

  SpecialistSignupController(Applications applications) {
    this.applications = applications;
  }

  record SpecialistSignupBody(
      @NotBlank @Size(max = 120) String fullName,
      @NotNull @Pattern(regexp = "^[+][1-9][0-9]{7,14}$") String phone,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 40) String termsVersion,
      @NotBlank @Size(max = 40) String codeOfConductVersion,
      @NotNull @Pattern(regexp = "en|fr") String appLanguage) {}

  @PostMapping("/v1/specialist-signups")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void start(@Valid @RequestBody SpecialistSignupBody body) {
    applications.startSignup(
        new Applications.SignupRequest(
            body.fullName(),
            body.phone(),
            body.email(),
            body.termsVersion(),
            body.codeOfConductVersion(),
            Locale.of(body.appLanguage())));
  }
}
