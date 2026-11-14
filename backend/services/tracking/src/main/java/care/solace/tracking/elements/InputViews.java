package care.solace.tracking.elements;

import care.solace.tracking.elements.ElementLibrary.Kind;
import care.solace.tracking.elements.ElementLibrary.ValueType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The input views the app ships (Tracking Service Spec, Input views). A view is app code, so only a
 * migration registers one, and the registry is read once at start.
 */
@Component
public class InputViews {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param props each prop the view takes: {"type": "integer"|"string"|"boolean"}, with "minimum"
   *     and "maximum" for an integer
   * @param minAppVersion the oldest app build that ships the view
   */
  public record InputView(
      String key,
      List<ValueType> valueTypes,
      List<Kind> kinds,
      JsonNode props,
      String minAppVersion) {

    public boolean accepts(Kind kind, ValueType valueType) {
      return kinds.contains(kind) && valueTypes.contains(valueType);
    }
  }

  private final List<InputView> views;

  InputViews(JdbcClient db) {
    this.views =
        db.sql("SELECT * FROM input_view ORDER BY position")
            .query(
                (rs, n) -> {
                  try {
                    return new InputView(
                        rs.getString("key"),
                        strings(rs.getArray("value_types")).stream()
                            .map(ValueType::valueOf)
                            .toList(),
                        strings(rs.getArray("kinds")).stream().map(Kind::valueOf).toList(),
                        JSON.readTree(rs.getString("props_schema_json")),
                        rs.getString("min_app_version"));
                  } catch (JsonProcessingException e) {
                    throw new SQLException("Bad input view JSON", e);
                  }
                })
            .list();
  }

  /** For tests. */
  public InputViews(List<InputView> views) {
    this.views = List.copyOf(views);
  }

  public List<InputView> all() {
    return views;
  }

  public Optional<InputView> find(String key) {
    return views.stream().filter(v -> v.key().equals(key)).findFirst();
  }

  private static List<String> strings(Array array) throws SQLException {
    return Arrays.asList((String[]) array.getArray());
  }
}
