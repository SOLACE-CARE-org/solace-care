package care.solace.tracking.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The launch histories as V1_11 seeds them, read from the migration itself. */
final class SeededHistories {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ROW =
      Pattern.compile(
          "\\('0e1f6a10-0008-[^']+', '([a-z0-9_]+)',\\s*'(\\{[^']*})',\\s*'(\\{[^']*})',\\s*'(\\{[^']*})'");

  record Seed(String code, JsonNode labels, JsonNode recipe, JsonNode context) {}

  private SeededHistories() {}

  static List<Seed> all() throws Exception {
    String sql;
    try (InputStream in =
        SeededHistories.class.getResourceAsStream("/db/migration/V1_11__synthetic_histories.sql")) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<Seed> out = new ArrayList<>();
    Matcher m = ROW.matcher(sql);
    while (m.find()) {
      out.add(
          new Seed(
              m.group(1),
              JSON.readTree(m.group(2)),
              JSON.readTree(m.group(3)),
              JSON.readTree(m.group(4))));
    }
    return out;
  }

  static List<SyntheticHistories.History> built(LocalDate today) throws Exception {
    return all().stream()
        .map(s -> SyntheticHistories.build(s.code(), s.recipe(), s.context(), today))
        .toList();
  }
}
