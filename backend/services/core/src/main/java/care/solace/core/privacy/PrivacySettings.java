package care.solace.core.privacy;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Privacy settings ({@code solace.privacy.*}).
 *
 * @param offeredPurposes the purposes the app asks about in this release. The privacy-choices
 *     screen hides the health check and tracking rows until those phases ship (docs/screens.md),
 *     which leaves AI assistance and AI session notes in Phase 1
 * @param wordingVersions the current version of each purpose's wording; a choice records the
 *     version the person saw. Defaults to {@code v1}
 */
@ConfigurationProperties("solace.privacy")
record PrivacySettings(
    @DefaultValue({"AI_ASSIST", "AI_SCRIBE"}) List<Purpose> offeredPurposes,
    Map<Purpose, String> wordingVersions) {

  String versionOf(Purpose purpose) {
    return wordingVersions == null ? "v1" : wordingVersions.getOrDefault(purpose, "v1");
  }
}
