package care.solace.tracking.wording;

import static org.assertj.core.api.Assertions.assertThat;

import care.solace.tracking.wording.WordList.Entry;
import care.solace.tracking.wording.WordList.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The launch word list, read from the migration that seeds it (D51, D61). */
class WordListTest {

  private static WordList launch;

  @BeforeAll
  static void readTheMigration() throws IOException {
    String sql;
    try (InputStream in =
        WordListTest.class.getResourceAsStream(
            "/db/migration/V1_13__wording_list_and_settings.sql")) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Entry> entries = new ArrayList<>();
    Matcher m =
        Pattern.compile("\\(gen_random_uuid\\(\\), '(en|fr)', '((?:[^']|'')*)', '(BLOCK|ALLOW)'\\)")
            .matcher(sql);
    while (m.find()) {
      entries.add(new Entry(m.group(1), m.group(2).replace("''", "'"), Kind.valueOf(m.group(3))));
    }
    assertThat(entries).hasSizeGreaterThan(40);
    entries.forEach(e -> assertThat(WordList.valid(e.pattern())).as(e.pattern()).isTrue());
    launch = new WordList(entries);
  }

  @Test
  void theLaunchListBlocksDiagnosticWordingAsTheCodeListDid() {
    assertThat(launch.allowed("Your last {n} cycles were longer than {threshold} days.", "en"))
        .isTrue();
    assertThat(launch.allowed("This is not a diagnosis.", "en")).isTrue();
    assertThat(launch.allowed("Ce n’est pas un diagnostic.", "fr")).isTrue();
    assertThat(launch.blockedBy("You may have PCOS.", "en")).containsExactly("pcos");
    assertThat(launch.allowed("Signs of depression", "en")).isFalse();
    assertThat(launch.allowed("Un trouble du cycle", "fr")).isFalse();
    assertThat(launch.allowed("Sévère", "fr")).isFalse();
    assertThat(launch.blockedBy("A diagnostic test", "en")).containsExactly("diagnos*");
    assertThat(launch.allowed("Vous avez une maladie", "fr")).isFalse();
  }

  @Test
  void wholeWordsOnlyUnlessTheStemSaysOtherwise() {
    var list = new WordList(List.of(new Entry("en", "ill", Kind.BLOCK)));
    assertThat(list.allowed("Fill in your log", "en")).isTrue();
    assertThat(list.allowed("Feeling ill", "en")).isFalse();
    // A language's list applies to messages in that language.
    assertThat(list.allowed("Feeling ill", "fr")).isTrue();
  }

  @Test
  void patternsAreStoredFoldedAndOnlySafeShapesAreAccepted() {
    assertThat(WordList.normalise("  Gravité  Élevée ")).isEqualTo("gravite elevee");
    assertThat(WordList.valid("diagnos*")).isTrue();
    assertThat(WordList.valid("a")).isFalse();
    assertThat(WordList.valid("(a|b)+")).isFalse();
    assertThat(WordList.valid("*ill")).isFalse();
  }
}
