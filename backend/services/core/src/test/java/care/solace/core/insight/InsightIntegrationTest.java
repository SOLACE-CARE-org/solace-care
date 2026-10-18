package care.solace.core.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The overview and analytics read counts only (D46). */
class InsightIntegrationTest extends SpecialistFlowTest {

  @Test
  void everyStaffRoleSeesTheOverviewWithServiceHealth() throws Exception {
    mvc.perform(get("/admin/v1/overview").with(staff("ce", "C", "CONTENT_EDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.health[0].service").value("CORE"))
        .andExpect(jsonPath("$.health[0].status").value("HEALTHY"))
        .andExpect(jsonPath("$.attention.refunds").isNumber())
        .andExpect(jsonPath("$.sessionsNow").isArray());
  }

  @Test
  void searchesAreCountedForTheFunnelAndAnalyticsAreForSomeRoles() throws Exception {
    long before =
        json(mvc.perform(get("/admin/v1/analytics").with(staff("fin", "F", "FINANCE"))))
            .path("funnel")
            .path("searched")
            .asLong();
    mvc.perform(get("/v1/catalogue/specialists").param("language", "en"))
        .andExpect(status().isOk());
    mvc.perform(get("/v1/catalogue/specialists").param("language", "en").param("offset", "20"));
    mvc.perform(get("/admin/v1/analytics").with(staff("fin", "F", "FINANCE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.funnel.searched").value(before + 1))
        .andExpect(jsonPath("$.recommendationsToBookingPercent").doesNotExist());
    mvc.perform(get("/admin/v1/analytics").with(staff("cr", "C", "CRISIS_RESPONDER")))
        .andExpect(status().isForbidden());
    String csv =
        mvc.perform(
                get("/admin/v1/analytics/export")
                    .param("period", "LAST_7_DAYS")
                    .with(staff("fin", "F", "FINANCE")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("measure,value\nsearched,");
  }
}
