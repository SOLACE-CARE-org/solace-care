package care.solace.core.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Finds the member account behind a token, for any module serving a signed-in person. */
public interface Accounts {

  /**
   * The account a members-realm token belongs to. Empty for staff tokens, service tokens and
   * accounts that do not exist (any more).
   */
  Optional<Account> of(Jwt token);

  /**
   * Like {@link #of(Jwt)}, for requests only a member can make: answers 403 {@code
   * account.not-a-member} otherwise.
   */
  Account require(Jwt token);

  /** The name a seeker wants to be called; only their specialist sees it. */
  Optional<String> seekerDisplayName(UUID accountId);

  /** The account's app language, for messages about it. */
  Optional<java.util.Locale> appLanguage(UUID accountId);
}
