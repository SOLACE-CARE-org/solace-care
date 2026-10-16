package care.solace.locale;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The app languages: English and French. These are the UI languages, not session languages; session
 * languages are data (ISO 639 rows) owned by Core.
 */
public final class SupportedLocales {

  public static final Locale ENGLISH = Locale.ENGLISH;
  public static final Locale FRENCH = Locale.FRENCH;

  /** Used when nothing about the viewer says otherwise. */
  public static final Locale DEFAULT = ENGLISH;

  public static final List<Locale> ALL = List.of(ENGLISH, FRENCH);

  private SupportedLocales() {}

  /**
   * Maps a language tag such as {@code fr}, {@code fr-CM} or {@code en_GB} to EN or FR; empty for
   * anything else.
   */
  public static Optional<Locale> match(String tag) {
    if (tag == null || tag.isBlank()) {
      return Optional.empty();
    }
    String language = Locale.forLanguageTag(tag.trim().replace('_', '-')).getLanguage();
    return ALL.stream().filter(l -> l.getLanguage().equals(language)).findFirst();
  }

  /** Like {@link #match(String)}, falling back to {@link #DEFAULT}. */
  public static Locale orDefault(String tag) {
    return match(tag).orElse(DEFAULT);
  }
}
