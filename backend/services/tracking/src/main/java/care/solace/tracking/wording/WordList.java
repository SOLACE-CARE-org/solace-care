package care.solace.tracking.wording;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The diagnostic word list (Tracking Service Spec, What a rule is made of; D51, D61): what a rule
 * message may not say, in its language. Patterns are whole words matched without case or accents; a
 * trailing * matches any ending. ALLOW phrases are taken out first, so "this is not a diagnosis"
 * can be said. The reviewer checks everything else.
 */
public final class WordList {

  public enum Kind {
    BLOCK,
    ALLOW
  }

  public record Entry(String language, String pattern, Kind kind) {}

  /** Letters, digits, spaces, apostrophes and hyphens, with an optional trailing *. */
  private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9' -]*[a-z0-9]\\*?$");

  private record Compiled(String language, String pattern, Pattern regex) {}

  private final List<Compiled> blocks = new ArrayList<>();
  private final List<Entry> allows = new ArrayList<>();

  public WordList(List<Entry> entries) {
    for (Entry e : entries) {
      if (e.kind() == Kind.ALLOW) {
        allows.add(e);
      } else {
        String p = e.pattern();
        boolean stem = p.endsWith("*");
        String word = Pattern.quote(stem ? p.substring(0, p.length() - 1) : p);
        blocks.add(
            new Compiled(e.language(), p, Pattern.compile("\\b" + word + (stem ? "" : "\\b"))));
      }
    }
  }

  /** The patterns that block the message; empty when it may be said. */
  public List<String> blockedBy(String message, String language) {
    String text = fold(message);
    for (Entry allowed : allows) {
      if (allowed.language().equals(language)) {
        text = text.replace(allowed.pattern(), " ");
      }
    }
    List<String> out = new ArrayList<>();
    for (Compiled b : blocks) {
      if (b.language().equals(language) && b.regex().matcher(text).find()) {
        out.add(b.pattern());
      }
    }
    return out;
  }

  public boolean allowed(String message, String language) {
    return blockedBy(message, language).isEmpty();
  }

  /** A pattern as it is stored: folded, trimmed, single spaces. */
  public static String normalise(String pattern) {
    return fold(pattern).strip().replaceAll("\\s+", " ");
  }

  /** True for a stored pattern of 2 to 60 characters that the list can match safely. */
  public static boolean valid(String normalised) {
    return normalised != null
        && normalised.length() >= 2
        && normalised.length() <= 60
        && VALID.matcher(normalised).matches();
  }

  static String fold(String text) {
    String noAccents =
        Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    return noAccents.toLowerCase(Locale.ROOT).replace('’', '\'');
  }
}
