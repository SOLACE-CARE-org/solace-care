package care.solace.ai.homework;

import care.solace.lists.ReviewedList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The companion's questions, thanks and closing lines as a reviewed list (D61), in EN and FR. They
 * guide an exercise a specialist set, so a clinician drafts a change and another signs it off. Each
 * message keeps its placeholders and has no exclamation mark. The wording in messages.properties is
 * version 0.
 */
@Component
class CompanionCopyList implements ReviewedList {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");

  /** The signed-off wording in force; empty while messages.properties counts. */
  private static volatile Map<String, String[]> inForce = Map.of();

  private final ObjectMapper json;
  private final Map<String, String[]> defaults;

  CompanionCopyList(ObjectMapper json) {
    this.json = json;
    this.defaults = read();
  }

  /** The text in force for a companion message, or empty to use messages.properties. */
  static Optional<String> text(String key, Locale locale, Object... args) {
    String[] both = inForce.get(key);
    if (both == null) {
      return Optional.empty();
    }
    boolean fr = "fr".equals(locale.getLanguage());
    String plain = fr ? both[1] : both[0];
    return Optional.of(
        new MessageFormat(plain.replace("'", "''"), fr ? Locale.FRENCH : Locale.ENGLISH)
            .format(args));
  }

  @Override
  public String code() {
    return "ai.companion.copy";
  }

  @Override
  public String description() {
    return "The homework companion's questions and closing lines, in EN and FR";
  }

  @Override
  public JsonNode defaults() {
    ObjectNode out = json.createObjectNode();
    defaults.forEach((k, v) -> out.putObject(k).put("en", v[0]).put("fr", v[1]));
    return out;
  }

  @Override
  public void validate(JsonNode entries) {
    copy(entries);
  }

  @Override
  public void apply(JsonNode live, int version) {
    inForce = version == 0 ? Map.of() : copy(live);
  }

  /** Every companion message, both languages, the same placeholders, no exclamation mark. */
  private Map<String, String[]> copy(JsonNode entries) {
    if (!entries.isObject()) {
      throw new IllegalArgumentException("messages by key");
    }
    Map<String, String[]> out = new LinkedHashMap<>();
    for (var entry : defaults.entrySet()) {
      JsonNode m = entries.path(entry.getKey());
      String en = m.path("en").asText();
      String fr = m.path("fr").asText();
      if (en.isBlank()
          || fr.isBlank()
          || en.length() > 600
          || fr.length() > 600
          || en.indexOf('!') >= 0
          || fr.indexOf('!') >= 0
          || !placeholders(en).equals(placeholders(entry.getValue()[0]))
          || !placeholders(fr).equals(placeholders(entry.getValue()[1]))) {
        throw new IllegalArgumentException("message " + entry.getKey() + " is not usable");
      }
      out.put(entry.getKey(), new String[] {en, fr});
    }
    if (entries.size() != defaults.size()) {
      throw new IllegalArgumentException("only the companion's own messages");
    }
    return Map.copyOf(out);
  }

  private static Set<Integer> placeholders(String text) {
    Set<Integer> found = new TreeSet<>();
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      found.add(Integer.parseInt(m.group(1)));
    }
    return found;
  }

  private static Map<String, String[]> read() {
    Properties en = load("messages.properties");
    Properties fr = load("messages_fr.properties");
    Map<String, String[]> out = new java.util.TreeMap<>();
    for (String key : en.stringPropertyNames()) {
      if (key.startsWith("companion.")) {
        out.put(
            key,
            new String[] {
              en.getProperty(key).replace("''", "'"),
              fr.getProperty(key, en.getProperty(key)).replace("''", "'")
            });
      }
    }
    return out;
  }

  private static Properties load(String resource) {
    Properties p = new Properties();
    try (InputStream in = CompanionCopyList.class.getClassLoader().getResourceAsStream(resource)) {
      if (in != null) {
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return p;
  }
}
