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
 * @param exportBucket private object storage for copies of people's data
 * @param exportLifetime how long a copy can be downloaded ([7] days, notification-copy.md)
 * @param requestDeadline the deadline recorded on each data request ([30] days; the legal deadline
 *     is still open)
 * @param guestRetention guest contacts lose their phone this long after last proving it ([12]
 *     months, System Design)
 * @param messageRetention delivered messages are deleted after this ([180] days, D40)
 */
@ConfigurationProperties("solace.privacy")
record PrivacySettings(
    @DefaultValue({"AI_ASSIST", "AI_SCRIBE"}) List<Purpose> offeredPurposes,
    Map<Purpose, String> wordingVersions,
    @DefaultValue("solace-exports") String exportBucket,
    @DefaultValue("P7D") java.time.Duration exportLifetime,
    @DefaultValue("P30D") java.time.Duration requestDeadline,
    @DefaultValue("P365D") java.time.Duration guestRetention,
    @DefaultValue("P180D") java.time.Duration messageRetention) {

  String versionOf(Purpose purpose) {
    return wordingVersions == null ? "v1" : wordingVersions.getOrDefault(purpose, "v1");
  }
}
