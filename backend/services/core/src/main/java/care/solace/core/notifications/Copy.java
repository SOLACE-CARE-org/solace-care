package care.solace.core.notifications;

import java.util.Locale;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Message wording from {@code copy.properties} (English) and {@code copy_fr.properties}, copied
 * word for word from notification-copy.md. Any language other than French gets English.
 */
@Component
class Copy {

  private final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

  Copy() {
    messages.setBasename("care/solace/core/notifications/copy");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    // Every message goes through MessageFormat, so an apostrophe is always written '' in the files.
    messages.setAlwaysUseMessageFormat(true);
  }

  String text(String key, Locale locale, Object... arguments) {
    Locale language = "fr".equals(locale.getLanguage()) ? Locale.FRENCH : Locale.ROOT;
    return messages.getMessage(key, arguments, language);
  }
}
