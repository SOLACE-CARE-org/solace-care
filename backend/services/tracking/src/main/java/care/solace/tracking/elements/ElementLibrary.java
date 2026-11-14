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
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The element library (Tracking Service Spec, Elements): every published and retired element
 * version. Drafts and versions in review are not in it, so nothing can log them or bind them. It is
 * read at start, again when this instance publishes or retires an element (D61), and every {@code
 * solace.tracking.elements.refresh-seconds} so other instances catch up.
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
      String minAppVersion,
      JsonNode viewProps,
      String status) {

    /** A published element with no view props, as the launch catalogue and tests build them. */
    public Element(
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
      this(
          id,
          code,
          major,
          minor,
          kind,
          valueType,
          constraints,
          inputView,
          icon,
          signals,
          labels,
          minAppVersion,
          JSON.createObjectNode(),
          "PUBLISHED");
    }

    /** Modules may bind it; a retired version stays only so what people logged stays readable. */
    public boolean published() {
      return "PUBLISHED".equals(status);
    }

    public String version() {
      return minor == 0 ? String.valueOf(major) : major + "." + minor;
    }

    public boolean isNote() {
      return valueType == ValueType.TEXT;
    }
  }

  private record Snapshot(
      Map<UUID, Element> byId, Map<String, Element> byCodeVersion, List<Element> ordered) {}

  private final JdbcClient db;
  private volatile Snapshot snapshot;

  ElementLibrary(JdbcClient db) {
    this.db = db;
    reload();
  }

  /** Reads the library again; readers keep the old one until the new one is complete. */
  @Scheduled(
      fixedDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      initialDelayString = "${solace.tracking.elements.refresh-seconds:30}",
      timeUnit = TimeUnit.SECONDS)
  public void reload() {
    Map<UUID, Element> byId = new LinkedHashMap<>();
    Map<String, Element> byCodeVersion = new LinkedHashMap<>();
    db.sql(
            "SELECT * FROM element WHERE status IN ('PUBLISHED', 'RETIRED')"
                + " ORDER BY code, major, minor")
        .query(ElementLibrary::row)
        .list()
        .forEach(
            e -> {
              byId.put(e.id(), e);
              byCodeVersion.put(e.code() + "@" + e.version(), e);
            });
    snapshot =
        new Snapshot(Map.copyOf(byId), Map.copyOf(byCodeVersion), List.copyOf(byId.values()));
  }

  /** Published and retired versions, by code then version. */
  public List<Element> all() {
    return snapshot.ordered();
  }

  /** The versions a module may bind now. */
  public List<Element> published() {
    return snapshot.ordered().stream().filter(Element::published).toList();
  }

  public Optional<Element> byId(UUID id) {
    return Optional.ofNullable(snapshot.byId().get(id));
  }

  /**
   * @param version "1" or "1.1"
   */
  public Optional<Element> find(String code, String version) {
    String v =
        version != null && version.endsWith(".0")
            ? version.substring(0, version.length() - 2)
            : version;
    return Optional.ofNullable(snapshot.byCodeVersion().get(code + "@" + v));
  }

  /**
   * The newest version of an element code, for rules and bindings that name only the code: the
   * newest published one, or the newest retired one when none is published.
   */
  public Optional<Element> latest(String code) {
    var versions = snapshot.ordered().stream().filter(e -> e.code().equals(code)).toList();
    var published = versions.stream().filter(Element::published).toList();
    return (published.isEmpty() ? versions : published)
        .stream()
            .reduce(
                (a, b) ->
                    b.major() > a.major() || (b.major() == a.major() && b.minor() > a.minor())
                        ? b
                        : a);
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
          rs.getString("min_app_version"),
          JSON.readTree(rs.getString("view_props_json")),
          rs.getString("status"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new SQLException("Bad element JSON", e);
    }
  }
}
