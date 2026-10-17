package care.solace.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CoreApplicationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsCreateTheSharedTables() {
    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class))
        .contains("audit_log", "event_publication", "processed_event", "flyway_schema_history");
  }

  @Test
  void healthProbesAreOpen() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void everythingElseNeedsAToken() throws Exception {
    mvc.perform(get("/v1/anything"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.required"));
  }
}
