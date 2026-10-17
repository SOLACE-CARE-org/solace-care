package care.solace.core.notifications;

/**
 * The SMS rules in notification-copy.md: one standard message, at most 160 characters of the GSM-7
 * alphabet once placeholders are filled. A character outside it (ê, ç, an emoji...) switches the
 * whole message to the 70-character format and can double its cost.
 */
final class Sms {

  static final int MAX_LENGTH = 160;

  // GSM 03.38 basic character set.
  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  // Extension table: each costs two characters.
  private static final String EXTENDED = "^{}\\[~]|€\f";

  private Sms() {}

  /** Length in GSM-7 characters, or -1 when the text needs another alphabet. */
  static int gsm7Length(String text) {
    int length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (BASIC.indexOf(c) >= 0) {
        length += 1;
      } else if (EXTENDED.indexOf(c) >= 0) {
        length += 2;
      } else {
        return -1;
      }
    }
    return length;
  }

  /** Refuses a message that would not go as one standard SMS. */
  static String checked(String text) {
    int length = gsm7Length(text);
    if (length < 0 || length > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "SMS must be at most " + MAX_LENGTH + " GSM-7 characters, got " + length);
    }
    return text;
  }
}
