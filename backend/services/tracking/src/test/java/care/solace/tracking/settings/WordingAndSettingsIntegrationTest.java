package care.solace.tracking.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The word list and Tracking settings as data, changed in the portal (D61). */
class WordingAndSettingsIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-1", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-1", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  @Autowired private TrackingSettings settings;

  private ResultActions send(MockHttpServletRequestBuilder r, RequestPostProcessor who, String body)
      throws Exception {
    return mvc.perform(r.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void aClinicalReviewerMaintainsTheWordListAndItAppliesAtOnce() throws Exception {
    mvc.perform(get("/admin/v1/tracking/wording-rules").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.pattern == 'pcos' && @.language == 'en')]").isNotEmpty());
    String word = "cure" + UUID.randomUUID().toString().substring(0, 4).replaceAll("[0-9]", "x");
    String body =
        "{\"language\":\"en\",\"pattern\":\" " + word.toUpperCase() + "* \",\"kind\":\"BLOCK\"}";
    send(post("/admin/v1/tracking/wording-rules"), ops, body).andExpect(status().isForbidden());
    String id =
        json(send(post("/admin/v1/tracking/wording-rules"), reviewer, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pattern").value(word + "*"))
                .andExpect(jsonPath("$.createdBy").value("Dr. Fomba Grace")))
            .path("id")
            .asText();
    send(post("/admin/v1/tracking/wording-rules"), reviewer, body).andExpect(status().isConflict());
    send(
            post("/admin/v1/tracking/wording-rules"),
            reviewer,
            "{\"language\":\"en\",\"pattern\":\"(a|b)+\",\"kind\":\"BLOCK\"}")
        .andExpect(status().isBadRequest());
    String check = "{\"text\":\"Some " + word + "s for this\",\"language\":\"en\"}";
    send(post("/admin/v1/tracking/wording-check"), ops, check)
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.blockedBy[0]").value(word + "*"));
    // Only English messages: the French list is its own.
    send(
            post("/admin/v1/tracking/wording-check"),
            ops,
            "{\"text\":\"Some " + word + "s\",\"language\":\"fr\"}")
        .andExpect(jsonPath("$.allowed").value(true));
    mvc.perform(delete("/admin/v1/tracking/wording-rules/" + id).with(reviewer))
        .andExpect(status().isNoContent());
    send(post("/admin/v1/tracking/wording-check"), ops, check)
        .andExpect(jsonPath("$.allowed").value(true));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'tracking.wording.delete'",
                Integer.class))
        .isPositive();
  }

  @Test
  void operationsChangesASettingWithAReasonWithinItsLimits() throws Exception {
    mvc.perform(get("/admin/v1/tracking/settings").with(reviewer))
        .andExpect(jsonPath("$[?(@.key == 'snooze_days')].defaultValue").value(7))
        .andExpect(jsonPath("$[?(@.key == 'nudge_reminder_window_hours')].value").value(2))
        .andExpect(jsonPath("$[?(@.key == 'nudge_risk_pause_days')].value").value(14));
    String path = "/admin/v1/tracking/settings/snooze_days";
    send(put(path), reviewer, "{\"value\":10,\"reason\":\"Feedback\"}")
        .andExpect(status().isForbidden());
    send(put(path), ops, "{\"value\":10}").andExpect(status().isBadRequest());
    send(put(path), ops, "{\"value\":500,\"reason\":\"Too long\"}")
        .andExpect(status().isBadRequest());
    send(put("/admin/v1/tracking/settings/unknown"), ops, "{\"value\":1,\"reason\":\"x\"}")
        .andExpect(status().isNotFound());
    try {
      send(put(path), ops, "{\"value\":10,\"reason\":\"Seekers asked for longer\"}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.value").value(10))
          .andExpect(jsonPath("$.updatedBy").value("N. Ebai"));
      assertThat(settings.get(TrackingSettings.SNOOZE_DAYS)).isEqualTo(10);
      assertThat(
              jdbc.queryForObject(
                  "SELECT reason FROM audit_log WHERE action = 'tracking.setting.change'"
                      + " ORDER BY occurred_at DESC LIMIT 1",
                  String.class))
          .isEqualTo("Seekers asked for longer");
    } finally {
      jdbc.update("DELETE FROM tracking_setting WHERE key = 'snooze_days'");
    }
    assertThat(settings.get(TrackingSettings.SNOOZE_DAYS)).isEqualTo(7);
  }
}
