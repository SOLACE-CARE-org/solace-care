package care.solace.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.specialists.SpecialistFlowTest;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Notification copy edited on the portal (D61): in force at once, checked first, audited. */
class NotificationCopyIntegrationTest extends SpecialistFlowTest {

  @Autowired Copy copy;

  private final RequestPostProcessor editor = staff("ce-1", "C. Editor", "CONTENT_EDITOR");
  private final RequestPostProcessor finance = staff("fin-c", "F", "FINANCE");

  @Test
  void anEditInBothLanguagesIsInForceAndTheApprovedWordingCanComeBack() throws Exception {
    String key = "push.rate-session";
    var before = message(key);
    String approvedEn = before.path("approvedEn").asText();
    String approvedFr = before.path("approvedFr").asText();
    int version = before.path("version").asInt();

    change(
            key,
            version,
            "How did it go? You can rate it now.",
            "Comment ça s'est passé ? Notez-la.",
            editor)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.en").value("How did it go? You can rate it now."))
        .andExpect(jsonPath("$.updatedBy").value("C. Editor"));
    assertThat(copy.text(key, Locale.ENGLISH)).isEqualTo("How did it go? You can rate it now.");
    // An apostrophe is kept as written, not taken for MessageFormat quoting.
    assertThat(copy.text(key, Locale.FRENCH)).isEqualTo("Comment ça s'est passé ? Notez-la.");

    // Someone else's change in between is refused, then the approved wording comes back.
    change(key, version, approvedEn, approvedFr, editor)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("copy.changed"));
    change(key, version + 1, approvedEn, approvedFr, editor).andExpect(status().isOk());
    assertThat(copy.text(key, Locale.ENGLISH)).isEqualTo(approvedEn);

    mvc.perform(get("/admin/v1/notification-copy/" + key + "/history").with(editor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].version").value(version + 2));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'notifications.copy.change'"
                    + " AND target_id = ?",
                Integer.class,
                key))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void theRulesAreCheckedAndOnlyContentStaffChange() throws Exception {
    var sms = message("sms.rescheduled");
    int version = sms.path("version").asInt();
    String en = sms.path("approvedEn").asText();
    String fr = sms.path("approvedFr").asText();
    change("sms.rescheduled", version, en + " See you soon!", fr, editor)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("copy.wording"));
    change("sms.rescheduled", version, en, fr + " Merci de votre confiance.", editor)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("copy.sms-too-long"));
    change("sms.rescheduled", version, "Moved.", fr, editor)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("copy.placeholders"));
    change("sms.rescheduled", version, en, fr, finance).andExpect(status().isForbidden());
    mvc.perform(get("/admin/v1/notification-copy").with(finance)).andExpect(status().isForbidden());
  }

  private com.fasterxml.jackson.databind.JsonNode message(String key) throws Exception {
    for (var m : json(mvc.perform(get("/admin/v1/notification-copy").with(editor)))) {
      if (m.path("key").asText().equals(key)) {
        return m;
      }
    }
    throw new AssertionError("no message " + key);
  }

  private ResultActions change(
      String key, int version, String en, String fr, RequestPostProcessor who) throws Exception {
    return mvc.perform(
        put("/admin/v1/notification-copy/" + key)
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(
                    Map.of("version", version, "en", en, "fr", fr, "reason", "Clearer wording"))));
  }
}
