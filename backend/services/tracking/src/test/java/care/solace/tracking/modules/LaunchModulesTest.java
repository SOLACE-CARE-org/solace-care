package care.solace.tracking.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.tracking.TrackingStackTest;
import care.solace.tracking.wording.WordingRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The launch modules (Phase 2, epic 10; D54) are seeded as drafts with the spec's bindings and
 * rules. Only the card image and its alt text stand between them and clinical review.
 */
class LaunchModulesTest extends TrackingStackTest {

  @Autowired private WordingRules words;

  @Test
  void bothLaunchModulesWaitAsDraftsWithEveryRuleValid() throws Exception {
    var ops = staff("ops-launch", "N. Ebai", "OPERATIONS");
    JsonNode modules =
        json(mvc.perform(get("/admin/v1/tracking/modules").with(ops)).andExpect(status().isOk()));
    List<String> codes = new ArrayList<>();
    for (JsonNode m : modules) {
      codes.add(m.path("code").asText());
    }
    assertThat(codes).contains("pcos", "daily_checkin");

    for (JsonNode m : modules) {
      String code = m.path("code").asText();
      if (!code.equals("pcos") && !code.equals("daily_checkin")) {
        continue;
      }
      JsonNode draft = m.path("versions").get(m.path("versions").size() - 1);
      assertThat(draft.path("number").asText()).isEqualTo("1.0");
      JsonNode v =
          json(
              mvc.perform(get("/admin/v1/tracking/versions/" + draft.path("id").asText()).with(ops))
                  .andExpect(status().isOk()));
      assertThat(v.path("status").asText()).isIn("DRAFT", "IN_REVIEW", "PUBLISHED");
      List<String> problems = new ArrayList<>();
      v.path("problems").forEach(p -> problems.add(p.asText()));
      if (v.path("status").asText().equals("DRAFT")) {
        assertThat(problems).containsExactlyInAnyOrder("imageAlt", "image");
      }
      for (JsonNode r : v.path("rules")) {
        assertThat(r.path("problems")).isEmpty();
        assertThat(words.allowed(r.path("messageEn").asText(), "en")).isTrue();
        assertThat(words.allowed(r.path("messageFr").asText(), "fr")).isTrue();
      }
      assertThat(v.path("rules").size()).isEqualTo(code.equals("pcos") ? 4 : 2);
      assertThat(v.path("elements").size()).isEqualTo(code.equals("pcos") ? 6 : 3);
    }
  }
}
