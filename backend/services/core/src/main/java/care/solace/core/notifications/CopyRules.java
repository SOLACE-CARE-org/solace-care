package care.solace.core.notifications;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an edited message must keep (CLAUDE.md, Notifications; notification-copy.md; D61). Checked
 * for both languages before a change is saved:
 *
 * <ul>
 *   <li>The same placeholders as the approved wording, so the code fills them all.
 *   <li>No exclamation mark and no emoji (design system, copy rules).
 *   <li>Neutral wording for seekers and specialists: never a condition, a specialty, "therapy" or
 *       "mental health". Staff crisis messages name the category on purpose and are not checked.
 *   <li>An SMS stays in GSM-7, French avoids ê â î ô û ç ë ï, and the text outside its placeholders
 *       is no longer than the approved one, so it still fits 160 characters filled.
 * </ul>
 */
final class CopyRules {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");

  /** Words that would tell someone looking at the phone what the message is about. */
  private static final Set<String> REVEALING =
      Set.of(
          "therap",
          "mental health",
          "mental illness",
          "psychiatr",
          "psycholog",
          "counsel",
          "depress",
          "anxiety",
          "trauma",
          "pcos",
          "thérap",
          "santé mentale",
          "sante mentale",
          "maladie mentale",
          "dépress",
          "anxiété",
          "anxiete",
          "traumatisme",
          "sopk");

  private static final String FRENCH_SMS_AVOID = "êâîôûçëïÊÂÎÔÛÇËÏ";

  private CopyRules() {}

  enum Problem {
    PLACEHOLDERS,
    WORDING,
    SMS_CHARACTERS,
    SMS_TOO_LONG
  }

  /** The first rule {@code text} breaks, against the approved wording of the same message. */
  static Optional<Problem> check(String key, String text, String approved, Locale language) {
    if (!placeholders(text).equals(placeholders(approved)) || !formats(text)) {
      return Optional.of(Problem.PLACEHOLDERS);
    }
    if (text.indexOf('!') >= 0 || hasEmoji(text) || (!forStaff(key) && reveals(text))) {
      return Optional.of(Problem.WORDING);
    }
    if (key.startsWith("sms.")) {
      String outside = PLACEHOLDER.matcher(text).replaceAll("");
      String approvedOutside = PLACEHOLDER.matcher(approved).replaceAll("");
      if (Sms.gsm7Length(text) < 0
          || ("fr".equals(language.getLanguage()) && containsAny(text, FRENCH_SMS_AVOID))) {
        return Optional.of(Problem.SMS_CHARACTERS);
      }
      if (Sms.gsm7Length(outside) > Sms.gsm7Length(approvedOutside)) {
        return Optional.of(Problem.SMS_TOO_LONG);
      }
    }
    return Optional.empty();
  }

  /** Crisis alerts and other staff messages: names a category on purpose. */
  static boolean forStaff(String key) {
    return key.startsWith("category.")
        || key.startsWith("sms.crisis-")
        || key.startsWith("push.staff.")
        || key.startsWith("email.staff-")
        || key.startsWith("email.data-requests-due.");
  }

  /** Written as people write it; MessageFormat needs an apostrophe doubled. */
  static String toMessageFormat(String text) {
    return text.replace("'", "''");
  }

  static String fromMessageFormat(String pattern) {
    return pattern.replace("''", "'");
  }

  private static Set<Integer> placeholders(String text) {
    Set<Integer> found = new TreeSet<>();
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      found.add(Integer.parseInt(m.group(1)));
    }
    return found;
  }

  private static boolean formats(String text) {
    try {
      new MessageFormat(toMessageFormat(text), Locale.ROOT);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean reveals(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return REVEALING.stream().anyMatch(lower::contains);
  }

  private static boolean hasEmoji(String text) {
    return text.codePoints()
        .anyMatch(
            cp ->
                cp >= 0x1F000
                    || (cp >= 0x2600 && cp <= 0x27BF)
                    || cp == 0xFE0F
                    || Character.getType(cp) == Character.SURROGATE);
  }

  private static boolean containsAny(String text, String characters) {
    return text.chars().anyMatch(c -> characters.indexOf(c) >= 0);
  }
}
