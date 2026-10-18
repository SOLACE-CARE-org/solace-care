package care.solace.core.reviews;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Review settings ({@code solace.reviews.*}, D37). Not in the specs.
 *
 * @param window how long after the session it can be rated ([14] days)
 * @param minRatingsShown ratings needed before search and profiles show an average ([3])
 */
@ConfigurationProperties("solace.reviews")
record ReviewSettings(
    @DefaultValue("P14D") Duration window, @DefaultValue("3") int minRatingsShown) {}
