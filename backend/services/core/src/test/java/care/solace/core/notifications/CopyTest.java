package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** notification-copy.md's rules, checked on the copy files themselves (D38). */
class CopyTest {

  private final Copy copy = new Copy();

  @Test
  void everyMessageExistsInBothLanguages() throws Exception {
    Set<Object> en = keys("copy.properties");
    Set<Object> fr = keys("copy_fr.properties");
    assertThat(fr).containsExactlyInAnyOrderElementsOf(en);
  }

  /** The longest date, time, amount, method and link each SMS can carry (D38). */
  @Test
  void everySmsFitsOneStandardMessageInTheWorstCase() throws Exception {
    String link = "https://slc.cm/g/" + "A".repeat(14);
    for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.FRENCH}) {
      // The longest dates: "Wed 26 Aug", and "mer. 26 aout" once French SMS lose the û.
      String date = locale == Locale.FRENCH ? "mer. 26 aout" : "Wed 26 Aug";
      for (Object key : keys("copy.properties")) {
        String k = key.toString();
        if (!k.startsWith("sms.")) {
          continue;
        }
        String text =
            copy.text(
                k,
                locale,
                k.equals("sms.refund-sent") ? "1 000 000" : date,
                k.equals("sms.refund-sent") ? "Orange Money" : "23:59",
                link);
        assertThat(Sms.gsm7Length(text)).as(k + " " + locale + ": " + text).isBetween(1, 160);
      }
    }
  }

  @Test
  void apostrophesSurviveFormatting() {
    assertThat(copy.text("push.approved", Locale.ENGLISH)).startsWith("You're approved.");
    assertThat(copy.text("push.payment-received", Locale.FRENCH, "15 000"))
        .isEqualTo("Paiement reçu : 15 000 FCFA. Votre reçu est dans l'application.");
  }

  private static Set<Object> keys(String file) throws Exception {
    Properties p = new Properties();
    try (InputStream in =
        CopyTest.class.getResourceAsStream("/care/solace/core/notifications/" + file)) {
      p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }
    return p.keySet();
  }
}
