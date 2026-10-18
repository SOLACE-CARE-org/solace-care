package care.solace.core.invites;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The seeker's session languages, for matching in a language they talk in (D52). */
@Component
class InvitePreferences {

  private final care.solace.core.identity.SeekerPreferences seekers;

  InvitePreferences(care.solace.core.identity.SeekerPreferences seekers) {
    this.seekers = seekers;
  }

  List<String> sessionLanguages(UUID accountId) {
    var languages = seekers.sessionLanguages(accountId);
    return languages.isEmpty() ? List.of("fr", "en") : languages;
  }
}
