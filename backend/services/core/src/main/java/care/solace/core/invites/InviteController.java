package care.solace.core.invites;

import care.solace.core.catalog.Catalogue;
import care.solace.core.identity.Accounts;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Matched specialists for an invite (tag invites; D52). */
@RestController
class InviteController {

  static final int MATCHES = 5;

  private final Accounts accounts;
  private final Invites invites;
  private final SpecialistOffers specialists;
  private final Catalogue catalogue;
  private final InvitePreferences preferences;

  InviteController(
      Accounts accounts,
      Invites invites,
      SpecialistOffers specialists,
      Catalogue catalogue,
      InvitePreferences preferences) {
    this.accounts = accounts;
    this.invites = invites;
    this.specialists = specialists;
    this.catalogue = catalogue;
    this.preferences = preferences;
  }

  record Specialty(String code, String label) {}

  record Matches(List<Specialty> specialties, List<SpecialistOffers.Match> specialists) {}

  @GetMapping("/v1/me/invites/{recommendationId}/specialists")
  Matches matches(
      @AuthenticationPrincipal Jwt token, @PathVariable UUID recommendationId, Locale locale) {
    var account = accounts.require(token);
    var invite =
        invites
            .find(account.id(), recommendationId)
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    Map<String, String> labels =
        catalogue.specialties(locale).stream()
            .collect(Collectors.toMap(Catalogue.Entry::code, Catalogue.Entry::label));
    var languages = preferences.sessionLanguages(account.id());
    return new Matches(
        invite.specialtyCodes().stream()
            .map(c -> new Specialty(c, labels.getOrDefault(c, c)))
            .toList(),
        specialists.matches(invite.specialtyCodes(), languages, locale, MATCHES));
  }
}
