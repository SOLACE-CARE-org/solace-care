package care.solace.core.auditlog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code audit_log} for the Audit log screen (admin screen 18; D42). The table is
 * append-only; nothing here writes to it.
 */
@Repository
class AuditSearch {

  /** The screen's action filter, as patterns on the dotted action names Core writes. */
  enum Group {
    CONTACT_REVEALED("%.contact.reveal"),
    CRISIS_VIEWED("crisis.alert.read"),
    REFUND("payments.refund.%"),
    STATUS_CHANGE("specialist%", "bookings.%", "team.%"),
    SETTING_CHANGE("settings.%", "flags.%", "catalogue.%"),
    BREAK_GLASS("%break-glass%");

    final List<String> patterns;

    Group(String... patterns) {
      this.patterns = List.of(patterns);
    }
  }

  private static final Pattern BOOKING = Pattern.compile("(?i)^bk-([0-9a-f]{4,32})$");

  record Row(
      long id,
      Instant at,
      String actorType,
      String actorId,
      List<String> actorRoles,
      String action,
      String targetType,
      String targetId,
      String subjectId,
      String outcome,
      String reason,
      Map<String, String> details) {}

  /**
   * @param staffIds staff whose name matched {@code q}
   */
  record Query(
      Group group, String q, List<String> staffIds, Instant from, Instant to, Long before) {}

  private final JdbcClient db;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  AuditSearch(JdbcClient db) {
    this.db = db;
  }

  List<Row> search(Query query, int limit) {
    var where = new ArrayList<String>();
    var params = new LinkedHashMap<String, Object>();
    if (query.group() != null) {
      var or = new ArrayList<String>();
      for (int i = 0; i < query.group().patterns.size(); i++) {
        or.add("action LIKE :g" + i);
        params.put("g" + i, query.group().patterns.get(i));
      }
      where.add("(" + String.join(" OR ", or) + ")");
    }
    if (query.from() != null) {
      where.add("occurred_at >= :from");
      params.put("from", Timestamp.from(query.from()));
    }
    if (query.to() != null) {
      where.add("occurred_at < :to");
      params.put("to", Timestamp.from(query.to()));
    }
    if (query.before() != null) {
      where.add("id < :before");
      params.put("before", query.before());
    }
    String q = query.q() == null ? null : query.q().trim();
    if (q != null && !q.isEmpty()) {
      var or = new ArrayList<String>();
      var booking = BOOKING.matcher(q);
      if (booking.matches()) {
        or.add("(target_type = 'booking' AND replace(target_id, '-', '') LIKE :bk)");
        params.put("bk", booking.group(1).toLowerCase(Locale.ROOT) + "%");
      }
      or.add("target_id = :q");
      or.add("subject_id = :q");
      params.put("q", q);
      if (!query.staffIds().isEmpty()) {
        or.add("actor_id IN (:staff)");
        params.put("staff", query.staffIds());
      }
      where.add("(" + String.join(" OR ", or) + ")");
    }
    var spec =
        db.sql(
            "SELECT * FROM audit_log"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY id DESC LIMIT :n");
    for (var p : params.entrySet()) {
      spec = spec.param(p.getKey(), p.getValue());
    }
    return spec.param("n", limit).query(this::row).list();
  }

  @SuppressWarnings("unchecked")
  private Row row(ResultSet rs, int n) throws SQLException {
    var roles = rs.getArray("actor_roles");
    Map<String, String> details;
    try {
      details = json.readValue(rs.getString("details"), Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      details = Map.of();
    }
    return new Row(
        rs.getLong("id"),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getString("actor_type"),
        rs.getString("actor_id"),
        roles == null ? List.of() : Arrays.asList((String[]) roles.getArray()),
        rs.getString("action"),
        rs.getString("target_type"),
        rs.getString("target_id"),
        rs.getString("subject_id"),
        rs.getString("outcome"),
        rs.getString("reason"),
        details);
  }
}
