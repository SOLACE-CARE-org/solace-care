package care.solace.audit;

import care.solace.audit.AuditEntry.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes audit entries to the service's {@code audit_log} table, each in its own transaction. */
public class JdbcAuditLog implements AuditLog {

  private static final String INSERT =
      """
      INSERT INTO audit_log (actor_type, actor_id, actor_roles, action, target_type, target_id,
                             subject_id, outcome, reason, trace_id, details)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
      """;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate ownTransaction;
  private final Supplier<Actor> actor;
  private final Supplier<String> traceId;

  public JdbcAuditLog(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      Supplier<Actor> actor,
      Supplier<String> traceId) {
    this.jdbc = jdbc;
    this.ownTransaction = new TransactionTemplate(transactions);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.actor = actor;
    this.traceId = traceId;
  }

  @Override
  public void record(AuditEntry entry) {
    String details = toJson(entry);
    ownTransaction.executeWithoutResult(
        status ->
            jdbc.update(
                connection -> {
                  var statement = connection.prepareStatement(INSERT);
                  Array roles =
                      connection.createArrayOf(
                          "text", entry.actor().roles().toArray(String[]::new));
                  statement.setString(1, entry.actor().type().name());
                  statement.setString(2, entry.actor().id());
                  statement.setArray(3, roles);
                  statement.setString(4, entry.action());
                  statement.setString(5, entry.targetType());
                  statement.setString(6, entry.targetId());
                  statement.setString(7, entry.subjectId());
                  statement.setString(8, entry.outcome().name());
                  statement.setString(9, entry.reason());
                  statement.setString(10, traceId.get());
                  statement.setString(11, details);
                  return statement;
                }));
  }

  @Override
  public Actor currentActor() {
    return actor.get();
  }

  private static String toJson(AuditEntry entry) {
    try {
      return JSON.writeValueAsString(entry.details());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Audit details must be plain strings", e);
    }
  }
}
