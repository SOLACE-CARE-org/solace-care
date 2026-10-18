package care.solace.tracking.elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The element library (Tracking Service Spec, Elements). Elements change only by migration, so the
 * library is read once at start.
 */
@Component
public class ElementLibrary {

  private static final ObjectMapper JSON = new ObjectMapper();

  public enum Kind {
    DAY_STATE,
    EVENT
  }

  public enum ValueType {
    INT,
    MINUTES,
    ENUM,
    BOOL,
    DATE,
    TEXT
  }

  /**
   * @param constraints min, max, step, values
   * @param labels {"en": {...}, "fr": {...}}
   */
  public record Element(
      UUID id,
      String code,
      int major,
      int minor,
      Kind kind,
      ValueType valueType,
      JsonNode constraints,
      String inputView,
      String icon,
      List<String> signals,
      JsonNode labels,
      String minAppVersion) {

    public String version() {
      return minor == 0 ? String.valueOf(major) : major + "." + minor;
    }

    public boolean isNote() {
      return valueType == ValueType.TEXT;
    }
  }

  private final Map<UUID, Element> byId = new LinkedHashMap<>();
  private final Map<String, Element> byCodeVersion = new LinkedHashMap<>();

  ElementLibrary(JdbcClient db) {
    db.sql("SELECT * FROM element ORDER BY code, major, minor")
        .query(ElementLibrary::row)
        .list()
        .forEach(
            e -> {
              byId.put(e.id(), e);
              byCodeVersion.put(e.code() + "@" + e.version(), e);
            });
  }

  public List<Element> all() {
    return List.copyOf(byId.values());
  }

  public Optional<Element> byId(UUID id) {
    return Optional.ofNullable(byId.get(id));
  }

  /**
   * @param version "1" or "1.1"
   */
  public Optional<Element> find(String code, String version) {
    String v =
        version != null && version.endsWith(".0")
            ? version.substring(0, version.length() - 2)
            : version;
    return Optional.ofNullable(byCodeVersion.get(code + "@" + v));
  }

  /** The newest version of an element code, for rules and bindings that name only the code. */
  public Optional<Element> latest(String code) {
    return byId.values().stream()
        .filter(e -> e.code().equals(code))
        .reduce(
            (a, b) ->
                b.major() > a.major() || (b.major() == a.major() && b.minor() > a.minor()) ? b : a);
  }

  private static Element row(ResultSet rs, int n) throws SQLException {
    try {
      return new Element(
          rs.getObject("id", UUID.class),
          rs.getString("code"),
          rs.getInt("major"),
          rs.getInt("minor"),
          Kind.valueOf(rs.getString("kind")),
          ValueType.valueOf(rs.getString("value_type")),
          JSON.readTree(rs.getString("constraints_json")),
          rs.getString("input_view"),
          rs.getString("icon"),
          Arrays.asList((String[]) rs.getArray("signals").getArray()),
          JSON.readTree(rs.getString("labels_json")),
          rs.getString("min_app_version"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new SQLException("Bad element JSON", e);
    }
  }
}
