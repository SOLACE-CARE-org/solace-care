package care.solace.lists;

import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.jobs.Job;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every {@link ReviewedList} of this service and its versions (D61). The live version of each list
 * is put in force when the service starts and within a minute of a sign-off on any instance.
 */
public class ReviewedLists {

  private static final Logger LOG = LoggerFactory.getLogger(ReviewedLists.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  public enum Status {
    DRAFT,
    LIVE,
    RETIRED,
    DECLINED
  }

  public record Version(
      String list,
      int version,
      Status status,
      JsonNode entries,
      String note,
      String draftedBy,
      String draftedByName,
      Instant draftedAt,
      String decidedByName,
      Instant decidedAt,
      String checkResult) {}

  /** The staff member acting, with the roles the token gives. */
  public record Staff(String id, String name, java.util.Collection<String> roles) {}

  private final Map<String, ReviewedList> lists = new LinkedHashMap<>();
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Map<String, Integer> applied = new HashMap<>();

  public ReviewedLists(
      List<ReviewedList> lists, JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
    lists.forEach(l -> this.lists.put(l.code(), l));
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
  }

  public List<ReviewedList> all() {
    return List.copyOf(lists.values());
  }

  public ReviewedList list(String code) {
    var list = lists.get(code);
    if (list == null) {
      throw new SolaceException(CommonError.NOT_FOUND);
    }
    return list;
  }

  /** The live version of a list, or empty while the code's list is in force. */
  public Optional<Version> live(String code) {
    return jdbc
        .query(
            "SELECT * FROM reviewed_list_version WHERE list_code = ? AND status = 'LIVE'",
            ReviewedLists::version,
            code)
        .stream()
        .findFirst();
  }

  /** The entries in force: the live version's, or the code's. */
  public JsonNode inForce(String code) {
    return live(code).map(Version::entries).orElseGet(() -> list(code).defaults());
  }

  public List<Version> versions(String code) {
    list(code);
    return jdbc.query(
        "SELECT * FROM reviewed_list_version WHERE list_code = ? ORDER BY version DESC",
        ReviewedLists::version,
        code);
  }

  /** Puts every live version in force where it changed since the last time. */
  @Job(
      name = "${spring.application.name:service}.lists.reload",
      description = "Puts signed-off versions of the reviewed lists in force",
      every = "${solace.lists.refresh:PT30S}",
      caution = true)
  public synchronized void reload() {
    for (var list : lists.values()) {
      try {
        var live = live(list.code());
        int version = live.map(Version::version).orElse(0);
        if (applied.containsKey(list.code()) && applied.get(list.code()) == version) {
          continue;
        }
        list.apply(live.map(Version::entries).orElseGet(list::defaults), version);
        applied.put(list.code(), version);
      } catch (RuntimeException e) {
        LOG.warn("List {} could not be put in force; the last one stays", list.code(), e);
      }
    }
  }

  // --- Changes ----------------------------------------------------------------------------------

  public Version draft(String code, JsonNode entries, String note, Staff by) {
    var list = list(code);
    if (by.roles().stream().noneMatch(list.drafters()::contains)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    try {
      list.validate(entries);
    } catch (IllegalArgumentException e) {
      throw new SolaceException(ListError.INVALID);
    }
    Instant now = clock.instant();
    Integer next =
        transactions.execute(
            s -> {
              jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, code);
              int version =
                  jdbc.queryForObject(
                      "SELECT coalesce(max(version), 0) + 1 FROM reviewed_list_version"
                          + " WHERE list_code = ?",
                      Integer.class,
                      code);
              jdbc.update(
                  """
                  INSERT INTO reviewed_list_version (list_code, version, status, entries, note,
                                                     drafted_by, drafted_by_name, drafted_at)
                  VALUES (?, ?, 'DRAFT', CAST(? AS jsonb), ?, ?, ?, ?)
                  """,
                  code,
                  version,
                  write(entries),
                  note.strip(),
                  by.id(),
                  by.name(),
                  Timestamp.from(now));
              return version;
            });
    return version(code, next);
  }

  /** A different approver signs a draft off once its check passed; the live one retires. */
  public Version signOff(String code, int version, Staff by) {
    var list = list(code);
    var draft = draftOf(code, version);
    if (by.roles().stream().noneMatch(list.approvers()::contains)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    if (by.id().equals(draft.draftedBy())) {
      throw new SolaceException(ListError.SAME_PERSON);
    }
    var problem = list.check(draft.entries());
    if (problem.isPresent()) {
      throw new SolaceException(ListError.CHECK_FAILED);
    }
    String summary = list.checkSummary(draft.entries());
    Instant now = clock.instant();
    transactions.executeWithoutResult(
        s -> {
          jdbc.update(
              "UPDATE reviewed_list_version SET status = 'RETIRED' WHERE list_code = ?"
                  + " AND status = 'LIVE'",
              code);
          jdbc.update(
              """
              UPDATE reviewed_list_version
                 SET status = 'LIVE', decided_by = ?, decided_by_name = ?, decided_at = ?,
                     check_result = ?
               WHERE list_code = ? AND version = ? AND status = 'DRAFT'
              """,
              by.id(),
              by.name(),
              Timestamp.from(now),
              summary,
              code,
              version);
        });
    reload();
    return version(code, version);
  }

  public Version decline(String code, int version, Staff by) {
    var list = list(code);
    var draft = draftOf(code, version);
    if (by.roles().stream().noneMatch(list.approvers()::contains)) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    if (by.id().equals(draft.draftedBy())) {
      throw new SolaceException(ListError.SAME_PERSON);
    }
    jdbc.update(
        """
        UPDATE reviewed_list_version
           SET status = 'DECLINED', decided_by = ?, decided_by_name = ?, decided_at = ?
         WHERE list_code = ? AND version = ? AND status = 'DRAFT'
        """,
        by.id(),
        by.name(),
        Timestamp.from(clock.instant()),
        code,
        version);
    return version(code, version);
  }

  /** What the list's check says about a draft, without signing it off. */
  public Optional<String> check(String code, int version) {
    return list(code).check(draftOf(code, version).entries());
  }

  private Version draftOf(String code, int version) {
    var v = version(code, version);
    if (v.status() != Status.DRAFT) {
      throw new SolaceException(ListError.NOT_DRAFT);
    }
    return v;
  }

  private Version version(String code, int version) {
    return jdbc
        .query(
            "SELECT * FROM reviewed_list_version WHERE list_code = ? AND version = ?",
            ReviewedLists::version,
            code,
            version)
        .stream()
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private static Version version(ResultSet rs, int n) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Version(
        rs.getString("list_code"),
        rs.getInt("version"),
        Status.valueOf(rs.getString("status")),
        read(rs.getString("entries")),
        rs.getString("note"),
        rs.getString("drafted_by"),
        rs.getString("drafted_by_name"),
        rs.getTimestamp("drafted_at").toInstant(),
        rs.getString("decided_by_name"),
        decided == null ? null : decided.toInstant(),
        rs.getString("check_result"));
  }

  static String write(JsonNode value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
