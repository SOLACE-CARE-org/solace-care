package care.solace.logscrub;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from text bound for logs: phone numbers, emails, tokens, and the values of
 * fields whose name says they hold codes, secrets, notes, answers or other free text.
 *
 * <p>This is a safety net, not a licence. Patterns cannot recognise a name or a sentence about
 * someone's health, so code must never log names, free text or health data in the first place
 * (CLAUDE.md, Privacy and safety). Log ids and outcomes instead.
 */
public final class Scrubber {

  /** Replacement for a whole sensitive value. */
  public static final String REDACTED = "[redacted]";

  /**
   * Field names whose values are always removed, matched case-insensitively against {@code
   * name=value}, {@code name: value} and {@code "name":"value"}. Covers secrets, codes, contact
   * details, and free text such as notes, answers, review text and transcripts.
   */
  public static final Set<String> SENSITIVE_FIELDS =
      Set.of(
          "password",
          "passwd",
          "secret",
          "client_secret",
          "token",
          "access_token",
          "refresh_token",
          "id_token",
          "authorization",
          "code",
          "otp",
          "phone",
          "phone_number",
          "email",
          "name",
          "first_name",
          "last_name",
          "firstname",
          "lastname",
          "full_name",
          "note",
          "notes",
          "text",
          "body",
          "message",
          "answer",
          "answers",
          "transcript",
          "comment",
          "review",
          "dob",
          "date_of_birth",
          "address");

  private static final String FIELD_ALTERNATION =
      String.join(
          "|",
          SENSITIVE_FIELDS.stream()
              .sorted((a, b) -> b.length() - a.length())
              .map(Pattern::quote)
              .toList());

  // "field": "value" in JSON, including escaped quotes inside the value.
  private static final Pattern JSON_FIELD =
      Pattern.compile(
          "(\"(?:" + FIELD_ALTERNATION + ")\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"",
          Pattern.CASE_INSENSITIVE);

  // field=value or field: value up to the next separator.
  private static final Pattern KEY_VALUE =
      Pattern.compile(
          "(?<![\\w.-])((?:" + FIELD_ALTERNATION + ")\\s*[=:]\\s*)([^\\s,;&}\\]\"]+)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  // International numbers: + then 8 to 15 digits, allowing spaces, dots and dashes between them.
  private static final Pattern PHONE_INTERNATIONAL =
      Pattern.compile("(?<![\\w+])\\+[1-9](?:[\\s.-]?\\d){7,14}(?!\\d)");

  // Cameroon mobile numbers written locally: 6 then 8 digits, e.g. 677000000 or 6 77 00 00 00.
  private static final Pattern PHONE_CAMEROON_LOCAL =
      Pattern.compile("(?<![\\w.-])6(?:[\\s.-]?\\d){8}(?![\\d-])");

  private record Rule(Pattern pattern, String replacement) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(JSON_FIELD, "$1\"" + REDACTED + "\""),
          new Rule(BEARER, "$1" + REDACTED),
          new Rule(JWT, "[token]"),
          new Rule(KEY_VALUE, "$1" + REDACTED),
          new Rule(EMAIL, "[email]"),
          new Rule(PHONE_INTERNATIONAL, "[phone]"),
          new Rule(PHONE_CAMEROON_LOCAL, "[phone]"));

  private Scrubber() {}

  /** Returns {@code text} with personal data replaced; {@code null} stays {@code null}. */
  public static String scrub(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    for (Rule rule : RULES) {
      Matcher matcher = rule.pattern().matcher(result);
      if (matcher.find()) {
        result = matcher.replaceAll(rule.replacement());
      }
    }
    return result;
  }

  /** Whether a structured field (MDC key, key-value pair) must never be logged with its value. */
  public static boolean isSensitiveField(String name) {
    return name != null && SENSITIVE_FIELDS.contains(name.toLowerCase(Locale.ROOT));
  }

  /** Scrubs a structured field value, removing it entirely if the field is sensitive. */
  public static String scrubField(String name, String value) {
    return isSensitiveField(name) ? REDACTED : scrub(value);
  }
}
