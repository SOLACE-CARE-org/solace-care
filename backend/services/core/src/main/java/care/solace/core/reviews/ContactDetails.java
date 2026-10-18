package care.solace.core.reviews;

import java.util.regex.Pattern;

/**
 * Phone numbers, emails and links in review text and replies (D37): reviews are anonymous and
 * contact happens through Solace only.
 */
final class ContactDetails {

  static final String MASK = "•••";

  private static final Pattern CONTACT =
      Pattern.compile(
          "(?i)"
              // Phone numbers: 8 or more digits, spaces, dots or dashes allowed between them.
              + "(\\+?\\d[\\d .\\-]{6,}\\d)"
              // Emails.
              + "|([\\w.+-]+@[\\w-]+\\.[\\w.]+)"
              // Links and messaging handles.
              + "|(https?://\\S+|www\\.\\S+|wa\\.me/\\S+|t\\.me/\\S+)");

  private ContactDetails() {}

  static boolean in(String text) {
    return text != null && CONTACT.matcher(text).find();
  }

  static String masked(String text) {
    return text == null ? null : CONTACT.matcher(text).replaceAll(MASK);
  }
}
