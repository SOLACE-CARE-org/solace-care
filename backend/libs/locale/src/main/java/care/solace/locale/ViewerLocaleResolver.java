package care.solace.locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Picks the language for a request (System Design, Bilingual design):
 *
 * <ol>
 *   <li>the signed-in person's stored language, from the token's {@code locale} claim;
 *   <li>otherwise the first EN or FR entry in {@code Accept-Language};
 *   <li>otherwise English.
 * </ol>
 *
 * The language is never changed through this resolver; people change it in their profile.
 */
public class ViewerLocaleResolver implements LocaleResolver {

  private final Supplier<Optional<String>> storedLocale;

  /**
   * @param storedLocale the signed-in person's stored language tag, if there is one
   */
  public ViewerLocaleResolver(Supplier<Optional<String>> storedLocale) {
    this.storedLocale = storedLocale;
  }

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    return storedLocale
        .get()
        .flatMap(SupportedLocales::match)
        .or(() -> fromAcceptLanguage(request))
        .orElse(SupportedLocales.DEFAULT);
  }

  @Override
  public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
    throw new UnsupportedOperationException(
        "The language comes from the person's profile or Accept-Language");
  }

  private static Optional<Locale> fromAcceptLanguage(HttpServletRequest request) {
    String header = request.getHeader("Accept-Language");
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    try {
      return Locale.LanguageRange.parse(header).stream()
          .map(Locale.LanguageRange::getRange)
          .map(SupportedLocales::match)
          .flatMap(Optional::stream)
          .findFirst();
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }
}
