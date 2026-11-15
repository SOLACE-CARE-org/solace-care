package care.solace.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The Phase 2b gate (implementation plan, Dynamic tracking; D61), as far as the portal reaches
 * today:
 *
 * <ol>
 *   <li>The launch modules, as seeded in the database, behave as before on the full set of seeded
 *       synthetic histories: each rule fires only on the history it describes, and "unless" holds.
 *   <li>A new element and a new module built only through the admin API reach a phone and fire a
 *       recommendation, with no migration, no restart and no app release.
 *   <li>A new episode model and a new context question, built the same way, reach a phone too: a
 *       rule reads the model's episodes, fires on what one person logged, and the question's answer
 *       switches it off for another.
 * </ol>
 */
class PhaseGateIntegrationTest extends TrackingStackTest {

  private final RequestPostProcessor ops = staff("ops-gate", "N. Ebai", "OPERATIONS");
  private final RequestPostProcessor reviewer =
      staff("cr-gate", "Dr. Fomba Grace", "CLINICAL_REVIEWER");

  // --- 1. The launch modules behave as before -----------------------------------------------

  @Test
  void theLaunchModulesFireOnlyOnTheHistoriesTheyDescribe() throws Exception {
    Set<String> seeded = new HashSet<>();
    for (JsonNode h : json(mvc.perform(get("/admin/v1/tracking/synthetic-histories").with(ops)))) {
      if (h.path("seeded").asBoolean()) {
        seeded.add(h.path("code").asText());
      }
    }
    assertThat(seeded).hasSize(12);

    Map<String, Set<String>> fired = new LinkedHashMap<>();
    Map<String, Set<String>> blocked = new LinkedHashMap<>();
    for (String module : List.of("pcos", "daily_checkin")) {
      for (JsonNode r :
          json(
              mvc.perform(
                      post("/admin/v1/tracking/versions/" + launch(module) + "/test-run").with(ops))
                  .andExpect(status().isOk()))) {
        String history = r.path("history").asText();
        if (!seeded.contains(history)) {
          continue;
        }
        String rule = r.path("ruleCode").asText();
        if (r.path("fires").asBoolean()) {
          fired.computeIfAbsent(rule, k -> new TreeSet<>()).add(history);
        }
        if (r.path("blockedByUnless").asBoolean()) {
          blocked.computeIfAbsent(rule, k -> new TreeSet<>()).add(history);
        }
      }
    }
    assertThat(fired)
        .containsOnlyKeys(
            "pcos.long_cycles",
            "pcos.long_periods",
            "pcos.no_period",
            "pcos.heavy_flow",
            "checkin.low_mood",
            "checkin.short_sleep");
    assertThat(fired.get("pcos.long_cycles")).containsExactly("long_cycles");
    assertThat(fired.get("pcos.long_periods")).containsExactly("long_periods");
    assertThat(fired.get("pcos.no_period")).containsExactly("no_period_90_days");
    assertThat(fired.get("pcos.heavy_flow")).containsExactly("heavy_flow");
    assertThat(fired.get("checkin.low_mood")).containsExactly("low_mood_two_weeks");
    assertThat(fired.get("checkin.short_sleep")).containsExactly("short_sleep");
    // Treatment switches the long-cycles invitation off.
    assertThat(blocked.get("pcos.long_cycles")).contains("long_cycles_hormonal_treatment");
  }

  /** The id of a launch module's first version, as the migration seeded it. */
  private String launch(String code) throws Exception {
    for (JsonNode m : json(mvc.perform(get("/admin/v1/tracking/modules").with(ops)))) {
      if (m.path("code").asText().equals(code)) {
        JsonNode versions = m.path("versions");
        return versions.get(versions.size() - 1).path("id").asText();
      }
    }
    throw new AssertionError("no launch module " + code);
  }

  // --- 2. Built in the portal, on a phone without a release -------------------------------

  @Test
  void anElementAndAModuleBuiltOnlyInThePortalReachAPhoneAndFire() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    String element = "headache_" + suffix;
    String module = "head_" + suffix;

    // An element from a view the app already ships, reviewed by someone else, then published.
    String elementId =
        json(send(
                    post("/admin/v1/tracking/element-versions"),
                    ops,
                    """
                    {"code":"%s","kind":"DAY_STATE","valueType":"ENUM",
                     "constraints":{"values":["none","some","bad","worst"]},
                     "inputView":"choice_chips","viewProps":{},"icon":"brain",
                     "labels":{"en":{"name":"Headache","values":{"none":"None","some":"Some","bad":"Bad","worst":"Worst"}},
                               "fr":{"name":"Mal de tête","values":{"none":"Aucun","some":"Un peu","bad":"Fort","worst":"Très fort"}}}}
                    """
                        .formatted(element))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minAppVersion").value("1.0.0")))
            .path("id")
            .asText();
    String elementPath = "/admin/v1/tracking/element-versions/" + elementId;
    mvc.perform(post(elementPath + "/submission").with(ops)).andExpect(status().isOk());
    send(post(elementPath + "/review"), reviewer, "{\"approve\":true}").andExpect(status().isOk());
    mvc.perform(post(elementPath + "/publication").with(ops)).andExpect(status().isOk());

    // Two made-up histories next to the rule: one it must fire on, one it must not.
    history("bad_head_" + suffix, element, "worst", "Bad headaches", "Maux de tête forts");
    history("calm_head_" + suffix, element, "none", "No headaches", "Pas de maux de tête");

    // A module that binds it beside mood, charts it, and has one rule on it.
    String version = moduleDraft(module, element);
    send(
            put("/admin/v1/tracking/versions/" + version + "/presentation"),
            ops,
            "{\"trends\":[\"daily_value:" + element + "\"]}")
        .andExpect(status().isOk());
    send(
            post("/admin/v1/tracking/versions/" + version + "/rules"),
            ops,
            """
            {"code":"head.bad_days",
             "conditions":[{"signal":"daily_value","element":"%s","operator":"days_in_window",
                            "comparison":"eq","threshold":"worst","mode":"at_least","count":3,
                            "window":7,"minData":3}],
             "outcome":"INVITE","specialtyCodes":["neurology"],
             "messageEn":"You logged a very bad headache on {count} of the last {window} days.",
             "messageFr":"Vous avez noté un très fort mal de tête {count} jours sur les {window} derniers.",
             "cooldownDays":30}
            """
                .formatted(element))
        .andExpect(status().isCreated());
    var run =
        json(mvc.perform(post("/admin/v1/tracking/versions/" + version + "/test-run").with(ops)));
    Map<String, Boolean> fires = new LinkedHashMap<>();
    run.forEach(r -> fires.put(r.path("history").asText(), r.path("fires").asBoolean()));
    assertThat(fires).containsEntry("bad_head_" + suffix, true);
    assertThat(fires).containsEntry("calm_head_" + suffix, false);
    mvc.perform(get("/admin/v1/tracking/versions/" + version).with(ops))
        .andExpect(jsonPath("$.problems.length()").value(0));
    mvc.perform(post("/admin/v1/tracking/versions/" + version + "/submission").with(ops))
        .andExpect(status().isOk());
    send(post("/admin/v1/tracking/versions/" + version + "/review"), reviewer, "{\"approve\":true}")
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/tracking/versions/" + version + "/publication").with(ops))
        .andExpect(status().isOk());

    // The phone: the library and the module list carry everything the app needs to draw it.
    UUID user = UUID.randomUUID();
    mvc.perform(get("/v1/tracking/elements").with(seeker(user)))
        .andExpect(
            jsonPath("$[?(@.code == '%s')].inputView".formatted(element)).value("choice_chips"))
        .andExpect(jsonPath("$[?(@.code == '%s')].minAppVersion".formatted(element)).value("1.0.0"))
        .andExpect(
            jsonPath("$[?(@.code == '%s')].labels.fr.values.worst".formatted(element))
                .value("Très fort"));
    mvc.perform(get("/v1/tracking/modules").with(seeker(user)))
        .andExpect(jsonPath("$[?(@.code == '%s')].name.fr".formatted(module)).value("Maux de tête"))
        .andExpect(jsonPath("$[?(@.code == '%s')].elements.length()".formatted(module)).value(2));

    // Enrol, log three very bad days, and the rule fires.
    consent(user);
    send(post("/v1/tracking/modules/" + module + "/enrolment"), seeker(user), "{}")
        .andExpect(status().isNoContent());
    List<Map<String, Object>> entries = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      entries.add(entry(element, LocalDate.now().minusDays(i).toString(), "worst"));
    }
    send(
            post("/v1/tracking/sync/push"),
            seeker(user),
            JSON.writeValueAsString(
                Map.of("deviceId", "p", "entries", entries, "deletions", List.of())))
        .andExpect(status().isOk());
    var recs =
        json(
            mvc.perform(
                get("/v1/tracking/recommendations")
                    .with(seeker(user))
                    .header("Accept-Language", "fr")));
    assertThat(recs).hasSize(1);
    assertThat(recs.get(0).path("message").asText())
        .isEqualTo("Vous avez noté un très fort mal de tête 3 jours sur les 7 derniers.");
  }

  // --- 3. An episode model and a context question, built in the portal, on a phone ---------

  @Test
  void anEpisodeModelAndAContextQuestionBuiltOnlyInThePortalReachAPhone() throws Exception {
    String sfx = UUID.randomUUID().toString().substring(0, 6);
    String start = "attack_" + sfx;
    String pain = "pain_" + sfx;
    String model = "attacks_" + sfx;
    String question = "preventive_" + sfx;
    String module = "attacks_mod_" + sfx;

    // Two elements from views the app already ships.
    publish(
        "/admin/v1/tracking/element-versions",
        """
        {"code":"%s","kind":"EVENT","valueType":"DATE","constraints":{},"inputView":"event_marker",
         "viewProps":{},"icon":"brain",
         "labels":{"en":{"name":"Attack started"},"fr":{"name":"Début de crise"}}}
        """
            .formatted(start));
    publish(
        "/admin/v1/tracking/element-versions",
        """
        {"code":"%s","kind":"DAY_STATE","valueType":"ENUM",
         "constraints":{"values":["none","mild","strong"]},"inputView":"choice_chips",
         "viewProps":{},"icon":"brain",
         "labels":{"en":{"name":"Pain","values":{"none":"None","mild":"Mild","strong":"Strong"}},
                   "fr":{"name":"Douleur","values":{"none":"Aucune","mild":"Légère","strong":"Forte"}}}}
        """
            .formatted(pain));

    // The episode model: an attack starts when marked and lasts while there is pain.
    publish(
        "/admin/v1/tracking/episode-model-versions",
        """
        {"code":"%s","startElement":"%s","activityElement":"%s","activeValues":["mild","strong"],
         "gapDays":2,"span":"UNTIL_END",
         "metrics":[{"code":"length_days","kind":"LENGTH_DAYS","labels":{"en":"Attack length","fr":"Durée de la crise"}},
                    {"code":"strong_days","kind":"DAYS_WITH_VALUE","value":"strong",
                     "labels":{"en":"Strong days","fr":"Jours forts"}}],
         "labels":{"en":"Attack","fr":"Crise"}}
        """
            .formatted(model, start, pain));

    // The context question, asked when joining the module.
    publish(
        "/admin/v1/tracking/context-question-versions",
        """
        {"code":"%s","type":"YES_NO","scope":"ENROLMENT",
         "question":{"en":"Are you taking a preventive treatment?",
                     "fr":"Prenez-vous un traitement préventif ?"},"options":[]}
        """
            .formatted(question));

    // Made-up histories: strong attacks fire; the same attacks on a preventive treatment, and a
    // calm month, do not.
    String attacks =
        """
        {"parts":[{"pattern":"episodes","start":"%s","activity":"%s","lengths":[10,10,10],
                   "currentDay":2,"active":[{"value":"strong","days":3}],
                   "after":{"value":"none","days":2}}]}
        """
            .formatted(start, pain);
    syntheticHistory("strong_" + sfx, attacks, "{}", "Strong attacks", "Crises fortes");
    syntheticHistory(
        "treated_" + sfx,
        attacks,
        "{\"" + question + "\":true}",
        "Strong attacks, preventive treatment",
        "Crises fortes, traitement préventif");
    history("calm_" + sfx, pain, "none", "No pain", "Pas de douleur");

    // The module: both elements, the model's day on the card, the question on joining, one rule.
    String version = moduleDraft(module, start, pain);
    send(
            put("/admin/v1/tracking/versions/" + version + "/presentation"),
            ops,
            """
            {"episode":{"model":"%s","dayLabel":{"en":"attack day {{n}}","fr":"jour {{n}} de la crise"}},
             "trends":["%s.strong_days"]}
            """
                .formatted(model, model))
        .andExpect(status().isOk());
    send(
            put("/admin/v1/tracking/versions/" + version + "/questions"),
            ops,
            "[\"" + question + "\"]")
        .andExpect(status().isOk());
    send(
            post("/admin/v1/tracking/versions/" + version + "/rules"),
            ops,
            """
            {"code":"attacks.strong",
             "conditions":[{"signal":"%s.strong_days","operator":"consecutive_episodes",
                            "comparison":"gte","threshold":2,"count":2}],
             "unless":[{"question":"%s","equals":true}],
             "outcome":"INVITE","specialtyCodes":["neurology"],
             "messageEn":"Your last {n} attacks each had at least {threshold} strong days.",
             "messageFr":"Vos {n} dernières crises ont chacune eu au moins {threshold} jours forts.",
             "cooldownDays":30}
            """
                .formatted(model, question))
        .andExpect(status().isCreated());
    Map<String, Boolean> fires = new LinkedHashMap<>();
    Map<String, Boolean> blocked = new LinkedHashMap<>();
    for (JsonNode r :
        json(mvc.perform(post("/admin/v1/tracking/versions/" + version + "/test-run").with(ops)))) {
      fires.put(r.path("history").asText(), r.path("fires").asBoolean());
      blocked.put(r.path("history").asText(), r.path("blockedByUnless").asBoolean());
    }
    assertThat(fires).containsEntry("strong_" + sfx, true);
    assertThat(fires).containsEntry("treated_" + sfx, false);
    assertThat(blocked).containsEntry("treated_" + sfx, true);
    assertThat(fires).containsEntry("calm_" + sfx, false);
    mvc.perform(get("/admin/v1/tracking/versions/" + version).with(ops))
        .andExpect(jsonPath("$.problems.length()").value(0));
    mvc.perform(post("/admin/v1/tracking/versions/" + version + "/submission").with(ops))
        .andExpect(status().isOk());
    send(post("/admin/v1/tracking/versions/" + version + "/review"), reviewer, "{\"approve\":true}")
        .andExpect(status().isOk());
    mvc.perform(post("/admin/v1/tracking/versions/" + version + "/publication").with(ops))
        .andExpect(status().isOk());

    // On the phone: the question to ask, and the module that asks it.
    UUID untreated = UUID.randomUUID();
    mvc.perform(get("/v1/tracking/context-questions").with(seeker(untreated)))
        .andExpect(
            jsonPath("$[?(@.code == '%s')].labels.fr.question".formatted(question))
                .value("Prenez-vous un traitement préventif ?"));
    mvc.perform(get("/v1/tracking/modules").with(seeker(untreated)))
        .andExpect(jsonPath("$[?(@.code == '%s')].questions[0]".formatted(module)).value(question));

    // Two people log the same three strong attacks; one is on a preventive treatment.
    UUID treated = UUID.randomUUID();
    LocalDate first = LocalDate.now().minusDays(32);
    List<Map<String, Object>> entries = new ArrayList<>();
    for (LocalDate s : List.of(first, first.plusDays(10), first.plusDays(20))) {
      entries.add(entry(start, s.toString(), s.toString()));
      for (int d = 0; d < 3; d++) {
        entries.add(entry(pain, s.plusDays(d).toString(), "strong"));
      }
      entries.add(entry(pain, s.plusDays(3).toString(), "none"));
    }
    for (var who : Map.of(untreated, false, treated, true).entrySet()) {
      consent(who.getKey());
      send(
              post("/v1/tracking/modules/" + module + "/enrolment"),
              seeker(who.getKey()),
              "{\"answers\":{\"" + question + "\":" + who.getValue() + "}}")
          .andExpect(status().isNoContent());
      List<Map<String, Object>> fresh = new ArrayList<>();
      entries.forEach(
          e -> {
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("clientEntryId", UUID.randomUUID().toString());
            fresh.add(copy);
          });
      send(
              post("/v1/tracking/sync/push"),
              seeker(who.getKey()),
              JSON.writeValueAsString(
                  Map.of("deviceId", "p", "entries", fresh, "deletions", List.of())))
          .andExpect(status().isOk());
    }

    // The model finds the attacks on the phone.
    var episodes =
        json(mvc.perform(get("/v1/tracking/episodes/" + model).with(seeker(untreated))))
            .path("episodes");
    assertThat(episodes).hasSize(3);
    assertThat(episodes.get(0).path("metrics").path("strong_days").asInt()).isEqualTo(3);

    // The rule fires for the person not on treatment, and the answer switches it off for the
    // other.
    var recs =
        json(
            mvc.perform(
                get("/v1/tracking/recommendations")
                    .with(seeker(untreated))
                    .header("Accept-Language", "fr")));
    assertThat(recs).hasSize(1);
    assertThat(recs.get(0).path("message").asText())
        .isEqualTo("Vos 2 dernières crises ont chacune eu au moins 2 jours forts.");
    assertThat(json(mvc.perform(get("/v1/tracking/recommendations").with(seeker(treated)))))
        .isEmpty();
  }

  /** Drafts, submits, has someone else approve, and publishes; the draft must be ready. */
  private void publish(String base, String body) throws Exception {
    String id =
        json(send(post(base), ops, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems.length()").value(0)))
            .path("id")
            .asText();
    String path = base + "/" + id;
    mvc.perform(post(path + "/submission").with(ops)).andExpect(status().isOk());
    send(post(path + "/review"), reviewer, "{\"approve\":true}").andExpect(status().isOk());
    mvc.perform(post(path + "/publication").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  private void syntheticHistory(String code, String recipe, String context, String en, String fr)
      throws Exception {
    send(
            post("/admin/v1/tracking/synthetic-histories"),
            reviewer,
            """
            {"code":"%s","labels":{"en":"%s","fr":"%s"},"recipe":%s,"context":%s}
            """
                .formatted(code, en, fr, recipe, context))
        .andExpect(status().isCreated());
  }

  private void history(String code, String element, String value, String en, String fr)
      throws Exception {
    send(
            post("/admin/v1/tracking/synthetic-histories"),
            reviewer,
            """
            {"code":"%s","labels":{"en":"%s","fr":"%s"},
             "recipe":{"parts":[{"pattern":"constant","element":"%s","value":"%s","days":7}]},
             "context":{}}
            """
                .formatted(code, en, fr, element, value))
        .andExpect(status().isCreated());
  }

  /** A module draft with EN/FR details, a card image, and mood plus the new elements bound. */
  private String moduleDraft(String code, String... elements) throws Exception {
    String id =
        json(send(
                    post("/admin/v1/tracking/modules"),
                    ops,
                    "{\"code\":\"" + code + "\",\"accentColour\":\"track-pink\"}")
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    String image =
        json(mvc.perform(
                    multipart("/admin/v1/tracking/images")
                        .file(new MockMultipartFile("file", "c.jpg", "image/jpeg", jpeg()))
                        .with(ops))
                .andExpect(status().isCreated()))
            .path("id")
            .asText();
    send(
            put("/admin/v1/tracking/versions/" + id + "/details"),
            ops,
            JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("nameEn", "Headaches"),
                    Map.entry("nameFr", "Maux de tête"),
                    Map.entry("descriptionEn", "How bad your headaches are, day by day"),
                    Map.entry("descriptionFr", "L'intensité de vos maux de tête, jour après jour"),
                    Map.entry("imageId", image),
                    Map.entry("imageAltEn", "A calendar"),
                    Map.entry("imageAltFr", "Un calendrier"),
                    Map.entry("audienceEn", "For anyone who gets headaches"),
                    Map.entry("audienceFr", "Pour toute personne qui a des maux de tête"))))
        .andExpect(status().isOk());
    StringBuilder bound =
        new StringBuilder("[{\"code\":\"mood\",\"version\":\"1\",\"required\":true}");
    for (String element : elements) {
      bound
          .append(",{\"code\":\"")
          .append(element)
          .append("\",\"version\":\"1\",\"required\":true}");
    }
    send(put("/admin/v1/tracking/versions/" + id + "/elements"), ops, bound.append("]").toString())
        .andExpect(status().isOk());
    return id;
  }

  private ResultActions send(
      MockHttpServletRequestBuilder request, RequestPostProcessor who, String body)
      throws Exception {
    return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static Map<String, Object> entry(String code, String day, Object value) {
    return Map.of(
        "clientEntryId",
        UUID.randomUUID().toString(),
        "element",
        Map.of("code", code, "version", "1"),
        "observedOn",
        day,
        "timeZone",
        "Africa/Douala",
        "value",
        value,
        "recordedAt",
        Instant.now().toString());
  }

  private static byte[] jpeg() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "jpg", out);
    return out.toByteArray();
  }
}
