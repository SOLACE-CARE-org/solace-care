package care.solace.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Members for the People screens (admin screens 9 and 10; D45). Contacts leave this module only for
 * staff with People access, and the People screens mask them until a logged reveal.
 */
public interface MemberDirectory {

  Optional<Member> byId(UUID id);

  Optional<Member> byPhone(String e164);

  Optional<Member> byEmail(String email);

  /** The newest accounts first. */
  List<Member> newest(int limit);

  record Member(
      UUID id,
      Account.Type type,
      Account.Status status,
      Locale appLanguage,
      String phone,
      String email,
      Instant createdAt) {}
}
