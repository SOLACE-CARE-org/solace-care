package care.solace.core.messaging;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Placeholders as notification-copy.md wants them: dates and times in the recipient's time zone and
 * language, amounts with the language's grouping. French SMS lose the accents that would leave the
 * GSM-7 alphabet (D38).
 */
final class Formats {

  private Formats() {}

  static Locale language(Locale locale) {
    return locale != null && "fr".equals(locale.getLanguage()) ? Locale.FRENCH : Locale.ENGLISH;
  }

  /** "Mon 19 Oct", "lun. 19 oct." */
  static String date(Instant at, Locale locale, ZoneId zone) {
    return DateTimeFormatter.ofPattern("EEE d MMM", language(locale)).format(at.atZone(zone));
  }

  /** "18:00" */
  static String time(Instant at, ZoneId zone) {
    return DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(zone));
  }

  /** "15,000", "15 000", with an ordinary space so SMS stay GSM-7. */
  static String amount(long amount, Locale locale) {
    return NumberFormat.getIntegerInstance(language(locale))
        .format(amount)
        .replace(' ', ' ')
        .replace(' ', ' ');
  }

  /**
   * For French SMS placeholders: â ê î ô û ç ë ï become their plain letters (notification-copy.md).
   */
  static String gsmSafe(String text) {
    if (text == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (char c : text.toCharArray()) {
      out.append(
          switch (c) {
            case 'â' -> 'a';
            case 'ê', 'ë' -> 'e';
            case 'î', 'ï' -> 'i';
            case 'ô' -> 'o';
            case 'û' -> 'u';
            case 'ç' -> 'c';
            case 'Â' -> 'A';
            case 'Ê', 'Ë' -> 'E';
            case 'Î', 'Ï' -> 'I';
            case 'Ô' -> 'O';
            case 'Û' -> 'U';
            default -> c;
          });
    }
    return out.toString();
  }
}
