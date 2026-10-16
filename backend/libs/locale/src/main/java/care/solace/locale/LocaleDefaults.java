package care.solace.locale;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Default settings every service gets, at the lowest precedence so a service can still override
 * them:
 *
 * <ul>
 *   <li>a missing French message falls back to the English file ({@code messages.properties}), not
 *       to whatever language the server runs in;
 *   <li>message files are UTF-8, so French accents need no escaping.
 * </ul>
 */
public class LocaleDefaults implements EnvironmentPostProcessor {

  static final String SOURCE_NAME = "solaceLocaleDefaults";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                SOURCE_NAME,
                Map.of(
                    "spring.messages.fallback-to-system-locale", "false",
                    "spring.messages.encoding", "UTF-8")));
  }
}
