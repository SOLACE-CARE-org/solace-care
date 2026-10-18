package care.solace.core.content;

import java.util.Optional;

/**
 * The published version of a legal text (D44): {@code terms}, {@code privacy-notice}, {@code
 * specialist-terms}, {@code code-of-conduct}. Empty until the first version is published; the
 * configured version is then the one people accept.
 */
public interface LegalTexts {

  /** "v2" for the second published version. */
  Optional<String> currentVersion(String slug);
}
