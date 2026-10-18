package care.solace.core.specialists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Staff review of specialist applications (admin screens 4 to 6). Applicants go through the real
 * app flow; staff tokens are minted by the test with the staff realm as issuer, because that realm
 * needs a password and an authenticator code at sign-in.
 */
class AdminReviewIntegrationTest extends SpecialistFlowTest {

  @Test
  void aReviewerVerifiesEveryItemAndApproves() throws Exception {
    Applicant applicant = submittedApplicant("+237698000001", "Kamga Estelle");
    var ops = staff("ops-1", "Nadine Ebai", "OPERATIONS");

    mvc.perform(get("/admin/v1/specialist-applications").param("tab", "SUBMITTED").with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counts.submitted").value(org.hamcrest.Matchers.greaterThan(0)))
        .andExpect(
            jsonPath("$.items[?(@.id == '%s')].profession".formatted(applicant.id()))
                .value("Clinical psychologist"));

    act(post(app(applicant, "/review")).with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.assignedTo.name").value("Nadine Ebai"))
        .andExpect(jsonPath("$.verifiedItems").value(1)); // services: within the price bands

    // Approval waits for every item.
    act(post(app(applicant, "/approval-proposal")).with(ops))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.review-incomplete"));
    // The profile needs the bio in both languages first.
    item(applicant, ops, "PROFILE", "{\"verified\":true}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.item-not-ready"));

    item(applicant, ops, "IDENTITY", "{\"verified\":true}").andExpect(status().isOk());
    item(
            applicant,
            ops,
            "LICENCE",
            "{\"verified\":true,\"licenceValidUntil\":\"%s\"}"
                .formatted(LocalDate.now().plusYears(2)))
        .andExpect(jsonPath("$.licence.verification.by.name").value("Nadine Ebai"));
    act(put(app(applicant, "/bio"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"language\":\"fr\",\"text\":\"J'accompagne les adultes face à l'anxiété.\"}"))
        .andExpect(jsonPath("$.profile.bioFr").value("J'accompagne les adultes face à l'anxiété."));
    item(applicant, ops, "PROFILE", "{\"verified\":true}").andExpect(status().isOk());
    item(applicant, ops, "PAYOUT", "{\"verified\":true}").andExpect(status().isOk());
    language(applicant, ops, "en", "NATIVE").andExpect(status().isOk());
    language(applicant, ops, "fr", "PROFESSIONAL")
        .andExpect(jsonPath("$.verifiedItems").value(6))
        .andExpect(jsonPath("$.languages[1].verification.by.name").value("Nadine Ebai"));

    // Two people (D29): the reviewer proposes, someone else confirms.
    act(post(app(applicant, "/approval")).with(ops))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.approval-not-proposed"));
    act(post(app(applicant, "/approval-proposal")).with(ops))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.approvalProposal.by.name").value("Nadine Ebai"));
    act(post(app(applicant, "/approval")).with(ops))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.same-approver"));
    var second = staff("sa-2", "Jean Fotso", "SUPER_ADMIN");
    act(post(app(applicant, "/approval")).with(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.activity[0].kind").value("APPROVED"))
        .andExpect(jsonPath("$.activity[0].by.name").value("Jean Fotso"))
        .andExpect(jsonPath("$.activity[1].kind").value("APPROVAL_PROPOSED"))
        .andExpect(jsonPath("$.activity[1].by.name").value("Nadine Ebai"))
        .andExpect(jsonPath("$.profile.draftedLanguage").value("fr"));

    // The specialist now gets the specialist app.
    mvc.perform(get("/v1/me").headers(bearer(applicant.token())))
        .andExpect(jsonPath("$.applicationStatus").value("APPROVED"));
    // Every action left an audit entry naming the staff member, never the applicant.
    List<Map<String, Object>> audit =
        jdbc.queryForList(
            "SELECT action, actor_id FROM audit_log WHERE target_id = ? ORDER BY occurred_at",
            applicant.id());
    assertThat(audit)
        .extracting(row -> row.get("action"))
        .contains(
            "specialists.application.open",
            "specialists.application.verify",
            "specialists.application.verify-language",
            "specialists.application.bio",
            "specialists.application.propose-approval",
            "specialists.application.approve");
    assertThat(audit)
        .filteredOn(row -> row.get("action").equals("specialists.application.approve"))
        .allSatisfy(row -> assertThat(row.get("actor_id")).isEqualTo("sa-2"));
    assertThat(audit)
        .filteredOn(row -> !row.get("action").equals("specialists.application.approve"))
        .allSatisfy(row -> assertThat(row.get("actor_id")).isEqualTo("ops-1"));
  }

  @Test
  void aProposedApprovalIsWithdrawnWhenTheItemsChange() throws Exception {
    var ops = staff("ops-4", "Ops Four", "OPERATIONS");
    var second = staff("ops-5", "Ops Five", "OPERATIONS");
    Applicant applicant = fullyVerified("+237698000007", "Changed Mind", ops);
    act(post(app(applicant, "/approval-proposal")).with(ops)).andExpect(status().isOk());
    act(post(app(applicant, "/approval-proposal")).with(second))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.approval-pending"));

    // The second person lowers a language level: the proposal no longer stands.
    language(applicant, second, "fr", "BELOW_PROFESSIONAL")
        .andExpect(jsonPath("$.approvalProposal").doesNotExist())
        .andExpect(jsonPath("$.activity[0].kind").value("APPROVAL_WITHDRAWN"));
    act(post(app(applicant, "/approval")).with(second))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.approval-not-proposed"));

    // A proposal can also be declined outright.
    act(post(app(applicant, "/approval-proposal")).with(ops)).andExpect(status().isOk());
    act(delete(app(applicant, "/approval-proposal")).with(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.approvalProposal").doesNotExist());
  }

  @Test
  void openingADocumentIsAuditedFirst() throws Exception {
    Applicant applicant = submittedApplicant("+237698000002", "Manga Joel");
    var support = staff("support-1", "Awa Bello", "SUPPORT");
    JsonNode review = json(act(get(app(applicant, "")).with(support)));
    String licence = null;
    for (JsonNode d : review.path("documents")) {
      if (d.path("kind").asText().equals("LICENCE")) {
        licence = d.path("id").asText();
      }
    }

    byte[] body =
        act(get(app(applicant, "/documents/" + licence)).with(support))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(body).isEqualTo(PDF);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'specialists.document.read'"
                    + " AND target_id = ? AND subject_id = ? AND actor_id = 'support-1'",
                Integer.class,
                licence,
                applicant.id()))
        .isEqualTo(1);
  }

  @Test
  void thePermissionMatrixIsEnforcedOnTheServer() throws Exception {
    Applicant applicant = submittedApplicant("+237698000003", "Achu Brenda");
    String path = app(applicant, "");

    // Support and Finance may look, not act.
    act(get(path).with(staff("s", "S", "SUPPORT"))).andExpect(status().isOk());
    act(get(path).with(staff("f", "F", "FINANCE"))).andExpect(status().isOk());
    act(post(app(applicant, "/review")).with(staff("s", "S", "SUPPORT")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access.denied"));
    // Content editors and crisis responders do not see Specialists at all.
    act(get(path).with(staff("c", "C", "CONTENT_EDITOR"))).andExpect(status().isForbidden());
    act(get(path).with(staff("r", "R", "CRISIS_RESPONDER"))).andExpect(status().isForbidden());
    // A member token never counts, whatever roles it claims; nor does a service token.
    act(get(path).headers(bearer(applicant.token()))).andExpect(status().isForbidden());
    String service = serviceToken("tracking-service", "tracking-service-dev-secret");
    act(get(path).headers(bearer(service))).andExpect(status().isForbidden());
    act(get(path)).andExpect(status().isUnauthorized());
  }

  @Test
  void aChangeRequestGoesBackToTheApplicantAndAResubmissionStartsOver() throws Exception {
    Applicant applicant = submittedApplicant("+237698000004", "Ewane Paul");
    var ops = staff("ops-2", "Paul Ndi", "OPERATIONS");
    act(post(app(applicant, "/review")).with(ops)).andExpect(status().isOk());
    item(applicant, ops, "IDENTITY", "{\"verified\":true}").andExpect(status().isOk());

    act(post(app(applicant, "/change-request"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"The licence photo is blurred. Please upload a clearer one.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.changesRequested").value(true));
    mvc.perform(
            get("/admin/v1/specialist-applications").param("tab", "CHANGES_REQUESTED").with(ops))
        .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(applicant.id())).exists());

    // The applicant sees the reason and can edit again.
    mvc.perform(get("/v1/me/specialist-application").headers(bearer(applicant.token())))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(
            jsonPath("$.reviewNote")
                .value("The licence photo is blurred. Please upload a clearer one."));
    upload(applicant.token(), "LICENCE", new MockMultipartFile("file", "clear.pdf", "x", PDF))
        .andExpect(status().isOk());
    mvc.perform(post("/v1/me/specialist-application/submission").headers(bearer(applicant.token())))
        .andExpect(jsonPath("$.status").value("SUBMITTED"));

    // Everything is checked again.
    act(get(app(applicant, "")).with(ops))
        .andExpect(jsonPath("$.identity.verification").doesNotExist())
        .andExpect(jsonPath("$.changesRequested").doesNotExist())
        .andExpect(jsonPath("$.activity[0].kind").value("SUBMITTED"));
  }

  @Test
  void rejectionSuspensionAndReinstatement() throws Exception {
    var ops = staff("ops-3", "Ops", "OPERATIONS");
    var superAdmin = staff("sa-1", "Super", "SUPER_ADMIN");

    Applicant rejected = submittedApplicant("+237698000005", "Not Licensed");
    act(post(app(rejected, "/rejection"))
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"The licence could not be confirmed with the issuing body.\"}"))
        .andExpect(jsonPath("$.status").value("REJECTED"));
    act(post(app(rejected, "/review")).with(ops))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("specialist.not-under-review"));

    Applicant approved = approvedSpecialist("+237698000006", "Mbarga Solange", ops);
    mvc.perform(get("/admin/v1/specialists").param("query", "mbarga").with(ops))
        .andExpect(jsonPath("$[0].fullName").value("Mbarga Solange"))
        .andExpect(jsonPath("$[0].status").value("APPROVED"));
    String specialist = "/admin/v1/specialists/" + approved.id() + "/suspension";
    act(post(specialist)
            .with(ops)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Complaint under investigation.\"}"))
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
    // Only a Super admin reinstates (D26).
    act(delete(specialist).with(ops)).andExpect(status().isForbidden());
    act(delete(specialist).with(superAdmin)).andExpect(jsonPath("$.status").value("APPROVED"));
  }
}
