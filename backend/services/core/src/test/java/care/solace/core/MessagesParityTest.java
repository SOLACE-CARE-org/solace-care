package care.solace.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every backend string exists in English and French (CLAUDE.md, Language: "CI fails on a missing
 * key"), with no empty value and no exclamation mark.
 */
class MessagesParityTest {

  @ParameterizedTest
  @ValueSource(strings = {"messages", "care/solace/core/notifications/copy"})
  void englishAndFrenchHaveTheSameKeys(String basename) throws IOException {
    Properties en = load(basename + ".properties");
    Properties fr = load(basename + "_fr.properties");

    assertThat(fr.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
    for (Properties bundle : new Properties[] {en, fr}) {
      for (String key : bundle.stringPropertyNames()) {
        assertThat(bundle.getProperty(key)).as(key).isNotBlank().doesNotContain("!");
      }
    }
  }

  private static Properties load(String path) throws IOException {
    Properties properties = new Properties();
    try (var in = MessagesParityTest.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }
}
