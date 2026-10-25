package care.solace.core.notifications;

import care.solace.jobs.Job;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Message wording. A message staff edited on the portal wins (D61); otherwise the wording in {@code
 * copy.properties} (English) and {@code copy_fr.properties}, copied word for word from
 * notification-copy.md, its first version. Any language other than French gets English.
 */
@Component
class Copy {

  private static final Logger LOG = LoggerFactory.getLogger(Copy.class);
  private static final String BASE = "care/solace/core/notifications/copy";

  /** The approved wording of one message, as people write it. */
  record Approved(String key, String en, String fr) {}

  private final CopyStore store;
  private final Map<String, Approved> approved;
  private volatile Map<String, CopyStore.Edited> edited = Map.of();

  @org.springframework.beans.factory.annotation.Autowired
  Copy(CopyStore store) {
    this.store = store;
    this.approved = approved();
    reload();
  }

  /** The approved wording only, with no edits: for checks on the copy files themselves. */
  Copy() {
    this.store = null;
    this.approved = approved();
  }

  String text(String key, Locale locale, Object... arguments) {
    boolean french = "fr".equals(locale.getLanguage());
    var e = edited.get(key);
    String plain;
    if (e != null) {
      plain = french ? e.fr() : e.en();
    } else {
      var a = approved.get(key);
      if (a == null) {
        throw new IllegalArgumentException("No notification copy for " + key);
      }
      plain = french ? a.fr() : a.en();
    }
    return new MessageFormat(
            CopyRules.toMessageFormat(plain), french ? Locale.FRENCH : Locale.ENGLISH)
        .format(arguments);
  }

  /** Every message, in the order of copy.properties. */
  List<Approved> approvedCopy() {
    return List.copyOf(approved.values());
  }

  Map<String, CopyStore.Edited> editedCopy() {
    return edited;
  }

  /** Edited messages are read again so every instance follows a change within a minute. */
  @Job(
      name = "core.notifications.copy-reload",
      description = "Reloads the notification copy edited on the portal",
      every = "${solace.admin.settings-refresh:PT30S}",
      initialDelay = "${solace.admin.settings-refresh:PT30S}",
      locked = true)
  void reload() {
    if (store == null) {
      return;
    }
    try {
      edited = Map.copyOf(store.all());
    } catch (RuntimeException e) {
      LOG.warn("Reloading notification copy failed; the last copy loaded stays in force", e);
    }
  }

  private static Map<String, Approved> approved() {
    Map<String, String> en = read(BASE + ".properties");
    Map<String, String> fr = read(BASE + "_fr.properties");
    Map<String, Approved> all = new LinkedHashMap<>();
    en.forEach(
        (key, text) ->
            all.put(
                key,
                new Approved(
                    key,
                    CopyRules.fromMessageFormat(text),
                    CopyRules.fromMessageFormat(fr.getOrDefault(key, text)))));
    return Collections.unmodifiableMap(all);
  }

  /** The keys in file order, which Properties alone would lose. */
  private static Map<String, String> read(String resource) {
    Map<String, String> ordered = new LinkedHashMap<>();
    Properties properties =
        new Properties() {
          @Override
          public synchronized Object put(Object key, Object value) {
            ordered.put((String) key, (String) value);
            return super.put(key, value);
          }
        };
    try (InputStream in = Copy.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + resource);
      }
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ordered;
  }
}
