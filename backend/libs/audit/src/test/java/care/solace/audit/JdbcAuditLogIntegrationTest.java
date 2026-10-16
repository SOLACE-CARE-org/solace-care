package care.solace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import care.solace.audit.AuditEntry.Actor;
import care.solace.audit.AuditEntry.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = JdbcAuditLogIntegrationTest.App.class)
@Testcontainers
class JdbcAuditLogIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired AuditLog audit;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void recordsAReadByTheSignedInSpecialist() {
    signIn(memberToken("specialist-7", List.of("SPECIALIST", "offline_access")));

    String data =
        audit.readThen("tracking.entries.read", "tracking.entry", null, "seeker-3", () -> "rows");

    assertThat(data).isEqualTo("rows");
    Map<String, Object> row = lastRow();
    assertThat(row)
        .containsEntry("actor_type", "SPECIALIST")
        .containsEntry("actor_id", "specialist-7")
        .containsEntry("action", "tracking.entries.read")
        .containsEntry("subject_id", "seeker-3")
        .containsEntry("outcome", "ALLOWED");
    assertThat(row.get("actor_roles").toString()).contains("SPECIALIST").doesNotContain("offline");
  }

  @Test
  void recordsServiceCallsByClientId() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .issuer("http://localhost:8180/realms/solace-staff")
            .subject("service-account-uuid")
            .claim("client_id", "tracking-service")
            .issuedAt(Instant.now())
            .build();
    signIn(jwt);

    audit.denied("tracking.summary.read", "tracking.summary", null, "seeker-3");

    assertThat(lastRow())
        .containsEntry("actor_type", "SERVICE")
        .containsEntry("actor_id", "tracking-service")
        .containsEntry("outcome", "DENIED");
  }

  @Test
  void entryStaysWhenTheCallersTransactionRollsBack() {
    long before = count();

    transactions.executeWithoutResult(
        status -> {
          audit.record(entry("admin.refund.proposed"));
          status.setRollbackOnly();
        });

    assertThat(count()).isEqualTo(before + 1);
  }

  @Test
  void auditRowsCannotBeChangedOrRemoved() {
    audit.record(entry("admin.settings.changed"));

    assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'x'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void noSignedInActorIsAnError() {
    assertThatThrownBy(() -> audit.read("tracking.entries.read", "tracking.entry", null, "s"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void adminActionsKeepFieldNamesInDetails() {
    signIn(staffToken("staff-1", List.of("OPERATIONS")));

    audit.adminAction("admin.settings.changed", "setting", "slot-hold", Map.of("field", "minutes"));

    assertThat(lastRow())
        .containsEntry("actor_type", "STAFF")
        .containsEntry("target_id", "slot-hold");
    assertThat(lastRow().get("details").toString()).contains("\"field\": \"minutes\"");
  }

  private static AuditEntry entry(String action) {
    return new AuditEntry(
        Actor.system("test"), action, "thing", "1", null, Outcome.ALLOWED, null, null);
  }

  private Map<String, Object> lastRow() {
    return jdbc.queryForMap("SELECT * FROM audit_log ORDER BY id DESC LIMIT 1");
  }

  private long count() {
    return jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class);
  }

  private static void signIn(Jwt jwt) {
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  private static Jwt memberToken(String subject, List<String> roles) {
    return token("http://localhost:8180/realms/solace-members", subject, roles);
  }

  private static Jwt staffToken(String subject, List<String> roles) {
    return token("http://localhost:8180/realms/solace-staff", subject, roles);
  }

  private static Jwt token(String issuer, String subject, List<String> roles) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .issuer(issuer)
        .subject(subject)
        .claim("realm_access", Map.of("roles", roles))
        .issuedAt(Instant.now())
        .build();
  }

  @Configuration
  @EnableAutoConfiguration
  static class App {}
}
