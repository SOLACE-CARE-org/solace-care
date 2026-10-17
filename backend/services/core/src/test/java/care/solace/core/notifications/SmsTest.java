package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The SMS rules of notification-copy.md, checked on the real copy. */
class SmsTest {

  private final Copy copy = new Copy();

  @Test
  void signInCodeFitsOneStandardSmsInBothLanguagesAtTheLongestSettings() {
    // codeLength may go up to 10 digits and the lifetime to three digits of minutes.
    for (Locale language : new Locale[] {Locale.ENGLISH, Locale.FRENCH}) {
      String text = copy.text("sms.sign-in-code", language, "1234567890", "999");
      assertThat(Sms.gsm7Length(text)).as(text).isBetween(1, Sms.MAX_LENGTH);
    }
  }

  @Test
  void copyIsWordForWord() {
    assertThat(copy.text("sms.sign-in-code", Locale.ENGLISH, "482913", "10"))
        .isEqualTo(
            "Solace: your code is 482913. It expires in 10 min. Never share it with anyone.");
    assertThat(copy.text("sms.sign-in-code", Locale.FRENCH, "482913", "10"))
        .isEqualTo(
            "Solace : votre code est 482913. Il expire dans 10 min. Ne le donnez à personne.");
    assertThat(copy.text("sms.sign-in-code", Locale.of("de"), "1", "1")).startsWith("Solace: your");
  }

  @Test
  void refusesCharactersOutsideGsm7AndOverlongMessages() {
    assertThat(Sms.gsm7Length("Fenêtre")).isEqualTo(-1);
    assertThat(Sms.gsm7Length("à é è ù")).isEqualTo(7);
    assertThat(Sms.gsm7Length("[x]")).isEqualTo(5);
    assertThatThrownBy(() -> Sms.checked("a".repeat(161)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Sms.checked("ça va")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loggingSenderMasksTheContact() {
    assertThat(LoggingMessageSender.mask("+237670000101")).isEqualTo("+237*******01");
    assertThat(LoggingMessageSender.mask("a@b.cm")).isEqualTo("******");
  }
}
