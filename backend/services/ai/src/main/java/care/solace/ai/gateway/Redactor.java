package care.solace.ai.gateway;

import java.util.regex.Pattern;

/**
 * Takes contact details out of text before it reaches a model (System Design, Model gateway: PII
 * redaction). Names are not sent in the first place: features pass pseudonymous facts only.
 */
public final class Redactor {

  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
  private static final Pattern PHONE = Pattern.compile("(?<!\\w)\\+?\\d[\\d\\s().-]{7,}\\d");
  private static final Pattern URL = Pattern.compile("https?://\\S+");

  private Redactor() {}

  public static String redact(String text) {
    if (text == null) {
      return "";
    }
    String out = EMAIL.matcher(text).replaceAll("[email]");
    out = URL.matcher(out).replaceAll("[link]");
    return PHONE.matcher(out).replaceAll("[phone]");
  }
}
