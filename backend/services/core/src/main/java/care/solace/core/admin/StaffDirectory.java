package care.solace.core.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Staff as Core knows them (D42): names for audit and activity screens, last activity, and when
 * their sessions were ended, so a token issued before a role change, a new password or deactivation
 * stops working at once rather than when it expires.
 */
@Component
public class StaffDirectory {

  /** How often a request updates "last active"; the Team screen shows days, not seconds. */
  private static final Duration TOUCH = Duration.ofMinutes(1);

  record Member(
      String id,
      String name,
      String email,
      List<String> roles,
      String status,
      Instant addedAt,
      Instant lastActiveAt,
      Instant sessionsEndedAt) {}

  private record Gate(String status, Instant sessionsEndedAt, Instant touchedAt) {}

  private final JdbcClient db;
  private final Clock clock;
  private final Map<String, Gate> gates = new ConcurrentHashMap<>();

  StaffDirectory(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /**
   * Records that this staff member is active, and says whether their token may still be used.
   *
   * @param issuedAt the token's iat
   */
  boolean admit(String id, String name, List<String> roles, Instant issuedAt) {
    Instant now = clock.instant();
    Gate gate = gates.get(id);
    if (gate == null || gate.touchedAt().plus(TOUCH).isBefore(now)) {
      gate = touch(id, name, roles, now);
      gates.put(id, gate);
    }
    if ("DEACTIVATED".equals(gate.status())) {
      return false;
    }
    // Token times are whole seconds.
    return gate.sessionsEndedAt() == null
        || issuedAt == null
        || !issuedAt.isBefore(gate.sessionsEndedAt().truncatedTo(ChronoUnit.SECONDS));
  }

  private Gate touch(String id, String name, List<String> roles, Instant now) {
    return db.sql(
            """
            INSERT INTO staff_member (id, name, roles, status, added_at, last_active_at)
            VALUES (:id, :name, :roles, 'ACTIVE', :now, :now)
            ON CONFLICT (id) DO UPDATE
               SET last_active_at = :now,
                   status = CASE WHEN staff_member.status = 'INVITED' THEN 'ACTIVE'
                                 ELSE staff_member.status END
            RETURNING status, sessions_ended_at
            """)
        .param("id", id)
        .param("name", name)
        .param("roles", StaffRoles.only(roles).toArray(String[]::new))
        .param("now", Timestamp.from(now))
        .query(
            (rs, n) ->
                new Gate(
                    rs.getString("status"), instant(rs.getTimestamp("sessions_ended_at")), now))
        .single();
  }

  /** Names staff see in activity and audit screens, by staff id. */
  public Map<String, String> names(Collection<String> ids) {
    Map<String, String> names = new HashMap<>();
    if (ids.isEmpty()) {
      return names;
    }
    db.sql("SELECT id, name FROM staff_member WHERE id IN (:ids)")
        .param("ids", ids)
        .query(
            rs -> {
              names.put(rs.getString("id"), rs.getString("name"));
            });
    return names;
  }

  /** An active staff member who can be told something (D47). */
  public record Recipient(String id, String name, String email) {}

  /** Active staff holding a role, for staff notifications (D47). */
  public List<Recipient> activeWithRole(String role) {
    return db.sql(
            "SELECT id, name, email FROM staff_member WHERE status <> 'DEACTIVATED' AND :r = ANY(roles)")
        .param("r", role)
        .query(
            (rs, n) ->
                new Recipient(rs.getString("id"), rs.getString("name"), rs.getString("email")))
        .list();
  }

  /** Staff ids whose name contains the text, for searching the audit log. */
  public List<String> idsNamed(String text) {
    return db.sql("SELECT id FROM staff_member WHERE name ILIKE :q")
        .param("q", "%" + text.replace("%", "").replace("_", "") + "%")
        .query(String.class)
        .list();
  }

  List<Member> all() {
    return db.sql("SELECT * FROM staff_member ORDER BY added_at, name")
        .query(StaffDirectory::row)
        .list();
  }

  Optional<Member> find(String id) {
    return db.sql("SELECT * FROM staff_member WHERE id = :id")
        .param("id", id)
        .query(StaffDirectory::row)
        .optional();
  }

  void add(String id, String name, String email, List<String> roles, String by, Instant now) {
    db.sql(
            """
            INSERT INTO staff_member (id, name, email, roles, status, added_at, added_by)
            VALUES (:id, :name, :email, :roles, 'INVITED', :now, :by)
            """)
        .param("id", id)
        .param("name", name)
        .param("email", email)
        .param("roles", roles.toArray(String[]::new))
        .param("now", Timestamp.from(now))
        .param("by", by)
        .update();
  }

  /** New roles, a new password or deactivation: older tokens stop working (D7). */
  void endSessions(String id, List<String> roles, String status, Instant now) {
    db.sql(
            """
            UPDATE staff_member
               SET roles = COALESCE(:roles, roles), status = COALESCE(:status, status),
                   sessions_ended_at = :now
             WHERE id = :id
            """)
        .param("id", id)
        .param("roles", roles == null ? null : roles.toArray(String[]::new))
        .param("status", status)
        .param("now", Timestamp.from(now))
        .update();
    gates.remove(id);
  }

  private static Member row(ResultSet rs, int n) throws SQLException {
    var roles = rs.getArray("roles");
    return new Member(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("email"),
        roles == null ? List.of() : StaffRoles.only(Arrays.asList((String[]) roles.getArray())),
        rs.getString("status"),
        rs.getTimestamp("added_at").toInstant(),
        instant(rs.getTimestamp("last_active_at")),
        instant(rs.getTimestamp("sessions_ended_at")));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
