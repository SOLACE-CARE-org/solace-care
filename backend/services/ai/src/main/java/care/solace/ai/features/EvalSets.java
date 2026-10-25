package care.solace.ai.features;

import care.solace.ai.gateway.Feature;
import care.solace.lists.ReviewedList;
import care.solace.lists.ReviewedListGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every evaluation set as a reviewed list (D61): one per AI feature ({@code ai.eval.<feature>}) and
 * the safety monitor's set ({@code ai.eval.safety-monitor}). Clinicians add and change cases; a
 * second clinician signs a version off. The files under {@code /eval} are version 0.
 */
@Component
public class EvalSets implements ReviewedListGroup {

  public static final String SAFETY = "ai.eval.safety-monitor";

  private final ObjectMapper json;

  EvalSets(ObjectMapper json) {
    this.json = json;
  }

  public static String code(Feature feature) {
    return "ai.eval." + feature.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @Override
  public List<ReviewedList> lists() {
    List<ReviewedList> all = new ArrayList<>();
    for (Feature f : Feature.values()) {
      all.add(set(code(f), "Evaluation cases for " + f.name(), false));
    }
    all.add(
        set(SAFETY, "The safety monitor's set: crisis recall must reach 99% in EN and FR", true));
    return all;
  }

  private ReviewedList set(String code, String description, boolean safety) {
    String file = "/eval/" + code.substring("ai.eval.".length()) + ".json";
    return new ReviewedList() {
      @Override
      public String code() {
        return code;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public JsonNode defaults() {
        try (InputStream in = EvalSets.class.getResourceAsStream(file)) {
          return in == null
              ? json.createObjectNode().set("cases", json.createArrayNode())
              : json.readTree(in);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      /** Each case has an id once, a locale of en or fr, and what it tests. */
      @Override
      public void validate(JsonNode entries) {
        JsonNode cases = entries.path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
          throw new IllegalArgumentException("cases must be a non-empty list");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode c : cases) {
          String locale = c.path("locale").asText();
          boolean shaped =
              safety
                  ? c.path("text").isTextual() && c.has("crisis")
                  : c.has("input") && c.has("expect");
          if (!("en".equals(locale) || "fr".equals(locale)) || !shaped) {
            throw new IllegalArgumentException("each case needs a locale and what it tests");
          }
          String id = c.path("id").asText(c.path("text").asText());
          if (!ids.add(id)) {
            throw new IllegalArgumentException("case ids must be unique");
          }
        }
      }

      @Override
      public void apply(JsonNode live, int version) {
        // Read where they are used, from the version in force.
      }
    };
  }
}
