package care.solace.locale;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

class SupportedLocalesTest {

  @ParameterizedTest
  @ValueSource(strings = {"fr", "FR", "fr-CM", "fr_CM", " fr "})
  void recognisesFrench(String tag) {
    assertThat(SupportedLocales.match(tag)).contains(Locale.FRENCH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"de", "", "xx-YY"})
  void otherLanguagesFallBackToEnglish(String tag) {
    assertThat(SupportedLocales.match(tag)).isEmpty();
    assertThat(SupportedLocales.orDefault(tag)).isEqualTo(Locale.ENGLISH);
  }

  @Test
  void defaultsNeverFallBackToTheServerLanguage() {
    StandardEnvironment environment = new StandardEnvironment();
    new LocaleDefaults().postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.messages.fallback-to-system-locale"))
        .isEqualTo("false");
    assertThat(environment.getProperty("spring.messages.encoding")).isEqualTo("UTF-8");
  }
}
