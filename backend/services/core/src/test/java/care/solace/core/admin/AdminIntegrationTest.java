package care.solace.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Settings and flags, the team and the audit log (admin screens 18 to 20; D42), against real
 * Keycloak for staff accounts.
 */
class AdminIntegrationTest extends SpecialistFlowTest {

  @Autowired PlatformSettings platform;
  @Autowired FeatureFlags flags;

  private final RequestPostProcessor superAdmin = staff("sa-1", "J. Tabi", "SUPER_ADMIN");
  private final RequestPostProcessor ops = staff("ops-s", "N. Ebai", "OPERATIONS");

  @Test
  void aSettingChangesWithAReasonAndIsReadAtOnce() throws Exception {
    mvc.perform(get("/admin/v1/settings").with(ops))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.settings[?(@.key == 'solace.sessions.no-show-after')].value").value("15"))
        .andExpect(
            jsonPath("$.settings[?(@.key == 'solace.scheduling.hold')].editable").value(false));
    // Operations can look, not change.
    change("solace.sessions.no-show-after", "20", ops).andExpect(status().isForbidden());
    change("solace.sessions.no-show-after", "500", superAdmin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("admin.setting-invalid"));
    change("solace.scheduling.hold", "12", superAdmin)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("admin.setting-fixed"));

    change("solace.sessions.no-show-after", "20", superAdmin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value("20"))
        .andExpect(jsonPath("$.defaultValue").value("15"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.updatedBy").value("J. Tabi"));
    assertThat(platform.duration("solace.sessions.no-show-after", Duration.ofMinutes(15)))
        .isEqualTo(Duration.ofMinutes(20));
    change("solace.sessions.no-show-after", "15", superAdmin)
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(get("/admin/v1/settings/solace.sessions.no-show-after/history").with(ops))
        .andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[1].value").value("20"))
        .andExpect(jsonPath("$[1].reason").value("Testing the change"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'settings.change' AND reason = 'Testing the change'",
                Integer.class))
        .isGreaterThanOrEqualTo(2);

    // The lowest price cannot pass the highest.
    change("solace.specialists.pricing.min-price", "30000", superAdmin)
        .andExpect(status().isBadRequest());
  }

  @Test
  void flagsReachTheAppsAndLockedOnesStay() throws Exception {
    mvc.perform(get("/v1/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flags.cardPayments").value(false))
        .andExpect(jsonPath("$.flags.safetyMonitor").doesNotExist());
    flag("safetyMonitor", false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("admin.flag-locked"));
    flag("onCallSpecialistCall", true).andExpect(status().isOk());
    mvc.perform(get("/v1/config")).andExpect(jsonPath("$.flags.onCallSpecialistCall").value(true));
    assertThat(flags.enabled("onCallSpecialistCall")).isTrue();
    flag("onCallSpecialistCall", false).andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void aSuperAdminAddsStaffAndARoleChangeEndsTheirSessions() throws Exception {
    JsonNode added =
        json(
            mvc.perform(
                    post("/admin/v1/staff")
                        .with(superAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "name",
                                    "M. Atangana",
                                    "email",
                                    "m.atangana@staff.solace.test",
                                    "roles",
                                    List.of("CONTENT_EDITOR")))))
                .andExpect(status().isCreated()));
    String id = added.path("member").path("id").asText();
    assertThat(added.path("temporaryPassword").asText()).hasSize(16);
    assertThat(added.path("member").path("status").asText()).isEqualTo("INVITED");
    assertThat(added.path("member").path("secondFactor").asBoolean()).isFalse();
    // The same email twice is refused.
    mvc.perform(
            post("/admin/v1/staff")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "name", "X",
                            "email", "m.atangana@staff.solace.test",
                            "roles", List.of("SUPPORT")))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("admin.staff-email-taken"));
    // Only Super admins see the team.
    mvc.perform(get("/admin/v1/staff").with(ops)).andExpect(status().isForbidden());

    // A token issued before the role change stops working.
    var before =
        issuedAt(id, "M. Atangana", Instant.now().minusSeconds(60), "CONTENT_EDITOR", "SUPPORT");
    mvc.perform(get("/admin/v1/bookings").with(before)).andExpect(status().isOk());
    mvc.perform(
            put("/admin/v1/staff/" + id + "/roles")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"roles\":[\"CONTENT_EDITOR\",\"SUPPORT\"],\"reason\":\"Covering support\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles.length()").value(2));
    mvc.perform(get("/admin/v1/bookings").with(before)).andExpect(status().isUnauthorized());
    var after =
        issuedAt(id, "M. Atangana", Instant.now().plusSeconds(2), "CONTENT_EDITOR", "SUPPORT");
    mvc.perform(get("/admin/v1/bookings").with(after)).andExpect(status().isOk());

    // Super admins cannot lock themselves out.
    mvc.perform(
            post("/admin/v1/staff/sa-1/deactivation")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}"))
        .andExpect(status().is4xxClientError());
    mvc.perform(
            post("/admin/v1/staff/" + id + "/temporary-password")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Lost phone\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
    mvc.perform(
            post("/admin/v1/staff/" + id + "/deactivation")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Left the team\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEACTIVATED"));
    var later =
        issuedAt(id, "M. Atangana", Instant.now().plusSeconds(5), "CONTENT_EDITOR", "SUPPORT");
    mvc.perform(get("/admin/v1/bookings").with(later)).andExpect(status().isUnauthorized());
  }

  @Test
  void theAuditLogIsSearchedByGroupAndExported() throws Exception {
    change("solace.reviews.window", "21", superAdmin).andExpect(status().isOk());
    mvc.perform(get("/admin/v1/audit").with(ops).param("group", "SETTING_CHANGE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").value("settings.change"))
        .andExpect(jsonPath("$[0].actor.name").value("J. Tabi"))
        .andExpect(jsonPath("$[0].reason").value("Testing the change"))
        .andExpect(jsonPath("$[0].target.label").value("solace.reviews.window"));
    mvc.perform(get("/admin/v1/audit").with(ops).param("q", "Tabi"))
        .andExpect(jsonPath("$[0].actor.name").value("J. Tabi"));
    mvc.perform(get("/admin/v1/audit").with(staff("sup", "S", "SUPPORT")))
        .andExpect(status().isForbidden());
    String csv =
        mvc.perform(get("/admin/v1/audit/export").with(superAdmin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("id,at,actor");
    change("solace.reviews.window", "14", superAdmin);
  }

  @Test
  void theCatalogueNeedsBothLanguagesAndAPolicyVersionAppliesFromNowOn() throws Exception {
    mvc.perform(get("/admin/v1/catalogue").with(staff("fin", "F", "FINANCE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.specialties[?(@.code == 'gynaecology')].enabled").value(false))
        .andExpect(jsonPath("$.durations[1]").value(50));
    mvc.perform(
            put("/admin/v1/catalogue/session-languages/pcm")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"labelEn\":\"Pidgin English\",\"enabled\":true,\"reason\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/admin/v1/catalogue/session-languages/pcm")
                .with(ops)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"labelEn\":\"Pidgin English\",\"labelFr\":\"Pidgin\",\"enabled\":false,"
                        + "\"reason\":\"Interviews in Pidgin start next month\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.position").value(3));
    mvc.perform(get("/v1/catalogue/session-languages"))
        .andExpect(jsonPath("$[?(@.code == 'pcm')]").isEmpty());

    String terms =
        "{\"fullRefundHours\":24,\"partialRefundHours\":2,\"partialRefundPercent\":50,"
            + "\"seekerNoShowRefundPercent\":0,\"specialistNoShowRefundPercent\":100,"
            + "\"maxReschedules\":2,\"rescheduleMinHours\":2,\"reason\":\"Same terms, new wording\"}";
    mvc.perform(
            post("/admin/v1/cancellation-policies")
                .with(staff("sup", "S", "SUPPORT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(terms))
        .andExpect(status().isForbidden());
    var published =
        json(
            mvc.perform(
                    post("/admin/v1/cancellation-policies")
                        .with(ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terms))
                .andExpect(status().isCreated()));
    assertThat(published.path("inForce").asBoolean()).isTrue();
    assertThat(published.path("publishedBy").asText()).isEqualTo("N. Ebai");
    mvc.perform(get("/admin/v1/cancellation-policies").with(ops))
        .andExpect(jsonPath("$[0].version").value(published.path("version").asInt()))
        .andExpect(jsonPath("$[1].inForce").value(false));
  }

  private org.springframework.test.web.servlet.ResultActions change(
      String key, String value, RequestPostProcessor who) throws Exception {
    return mvc.perform(
        put("/admin/v1/settings/" + key)
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(Map.of("value", value, "reason", "Testing the change"))));
  }

  private org.springframework.test.web.servlet.ResultActions flag(String key, boolean on)
      throws Exception {
    return mvc.perform(
        put("/admin/v1/flags/" + key)
            .with(superAdmin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enabled", on, "reason", "Trying it"))));
  }

  /** A staff token with an issue time, as Keycloak would sign it. */
  private static RequestPostProcessor issuedAt(
      String id, String name, Instant iat, String... roles) {
    String issuer = KEYCLOAK.getAuthServerUrl().replaceAll("/$", "") + "/realms/solace-staff";
    return jwt()
        .jwt(
            j ->
                j.subject(id)
                    .issuer(issuer)
                    .issuedAt(iat)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }
}
