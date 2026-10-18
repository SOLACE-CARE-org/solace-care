package care.solace.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import care.solace.core.bookings.BookingRecords;
import care.solace.core.specialists.SpecialistFlowTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money for real bookings through the sandbox aggregator (epic 6; D14, D33, D35): capture, release
 * less the 20% fee, policy refunds approved by a second person, signed webhooks, and a payout run
 * with two people. The specialist charges 15,000 FCFA for 50 minutes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentIntegrationTest extends SpecialistFlowTest {

  private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");
  private static final AtomicInteger PHONES = new AtomicInteger(300);
  private static final AtomicInteger WEEKS = new AtomicInteger(10);

  @Autowired BookingRecords bookings;
  @Autowired Payments payments;
  @Autowired PayoutRuns payoutRuns;
  @Autowired Ledger ledger;
  @Autowired TransactionTemplate transactions;

  private final RequestPostProcessor finance = staff("fin-a", "A. Fon", "FINANCE");
  private final RequestPostProcessor finance2 = staff("fin-b", "B. Tabi", "FINANCE");
  private final RequestPostProcessor support = staff("sup-a", "E. Nkeng", "SUPPORT");
  private final RequestPostProcessor ops = staff("ops-p", "R", "OPERATIONS");

  private Applicant specialist;
  private LocalDate monday;

  @BeforeAll
  void specialist() throws Exception {
    specialist = approvedSpecialist("+237696000201", "Pay Keeper", ops);
    mvc.perform(
        post("/v1/me/specialist-application/bio-confirmation")
            .headers(bearer(specialist.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    monday = LocalDate.now(DOUALA).plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
  }

  @Test
  void aPaidSessionIsHeldThenReleasedLessTheFee() throws Exception {
    String seeker = seeker();
    String bookingId = paidBooking(seeker);
    assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
        .isEqualTo("CONFIRMED");
    assertThat(ledger.balance(Ledger.Account.HELD, null, UUID.fromString(bookingId)))
        .isEqualTo(15000);
    long before = available();

    bookings.complete(UUID.fromString(bookingId), Instant.now());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(available()).isEqualTo(before + 12000));
    assertThat(ledger.balance(Ledger.Account.HELD, null, UUID.fromString(bookingId))).isZero();
    mvc.perform(get("/v1/me/earnings").headers(bearer(specialist.token())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activity[0].kind").value("SESSION"))
        .andExpect(jsonPath("$.activity[0].amount").value(12000))
        .andExpect(jsonPath("$.activity[0].seekerLabel").value("Payer"))
        .andExpect(jsonPath("$.payoutTo.method").value("MTN_MOMO"));
    // Seekers have no earnings.
    mvc.perform(get("/v1/me/earnings").headers(bearer(seeker)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("payment.not-a-specialist"));
  }

  @Test
  void aCancellationRefundNeedsFinanceAndReachesTheWallet() throws Exception {
    String seeker = seeker();
    String bookingId = paidBooking(seeker);
    mvc.perform(
            post("/v1/bookings/" + bookingId + "/cancellation")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundAmount").value(15000));

    JsonNode refund =
        await().atMost(Duration.ofSeconds(10)).until(() -> proposedFor(bookingId), r -> r != null);
    assertThat(refund.path("reason").asText()).isEqualTo("POLICY_CANCELLATION");
    assertThat(refund.path("proposedBy").path("system").asBoolean()).isTrue();
    assertThat(refund.path("canDecide").asBoolean()).isTrue();
    String refundId = refund.path("id").asText();

    // Change access to Payments is not enough: refunds are Finance's (D14).
    mvc.perform(post("/admin/v1/refunds/" + refundId + "/approval").with(ops))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin/v1/refunds/" + refundId + "/approval")
                .with(staff("sa", "S", "SUPER_ADMIN")))
        .andExpect(status().isForbidden());

    mvc.perform(post("/admin/v1/refunds/" + refundId + "/approval").with(finance))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SENT"))
        .andExpect(jsonPath("$.decidedBy").value("A. Fon"));
    payments.poll();
    mvc.perform(get("/admin/v1/refunds").with(finance).param("status", "PAID"))
        .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(refundId)).value("PAID"));
    assertThat(ledger.balance(Ledger.Account.HELD, null, UUID.fromString(bookingId))).isZero();
    assertThat(ledger.balance(Ledger.Account.REFUNDS_PAYABLE, null, UUID.fromString(bookingId)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'payments.refund.approve' AND target_id = ?",
                Integer.class,
                refundId))
        .isEqualTo(1);
  }

  @Test
  void whoeverProposesARefundCannotApproveIt() throws Exception {
    String bookingId = paidBooking(seeker());
    var both = staff("both", "Two Hats", "SUPPORT", "FINANCE");
    String refundId =
        json(mvc.perform(
                post("/admin/v1/bookings/" + bookingId + "/refunds")
                    .with(both)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":5000,\"reason\":\"Booked twice by mistake\"}")))
            .path("id")
            .asText();
    mvc.perform(post("/admin/v1/refunds/" + refundId + "/approval").with(both))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("money.same-person"));
    // More than was paid is refused; so is a second open refund.
    mvc.perform(
            post("/admin/v1/bookings/" + bookingId + "/refunds")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"reason\":\"Again\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("refund.already-open"));

    mvc.perform(post("/admin/v1/refunds/" + refundId + "/decline").with(finance2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DECLINED"));
    mvc.perform(
            post("/admin/v1/bookings/" + bookingId + "/refunds")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":20000,\"reason\":\"Too much\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("refund.too-large"));
  }

  @Test
  void webhooksAreSignedAndCountOnce() throws Exception {
    String seeker = seeker();
    String bookingId = book(seeker);
    JsonNode payment =
        json(
            mvc.perform(
                    post("/v1/bookings/" + bookingId + "/payments")
                        .headers(bearer(seeker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"ORANGE_MONEY\"}"))
                .andExpect(status().isCreated()));
    assertThat(payment.path("status").asText()).isEqualTo("PENDING");
    String providerRef =
        jdbc.queryForObject(
            "SELECT provider_ref FROM payment WHERE id = ?::uuid",
            String.class,
            payment.path("id").asText());
    byte[] body =
        ("{\"id\":\"evt-"
                + UUID.randomUUID()
                + "\",\"type\":\"payment.complete\",\"data\":{\"reference\":\""
                + providerRef
                + "\"}}")
            .getBytes(StandardCharsets.UTF_8);

    webhook(body, "not-the-signature").andExpect(status().isUnauthorized());
    webhook(body, null).andExpect(status().isUnauthorized());
    assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
        .isEqualTo("PENDING_PAYMENT");

    String signature = NotchPayFormat.sign("sandbox-webhook-secret", body);
    webhook(body, signature).andExpect(status().isNoContent());
    webhook(body, signature).andExpect(status().isNoContent());
    assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal WHERE kind = 'CAPTURE' AND booking_id = ?::uuid",
                Integer.class,
                bookingId))
        .isEqualTo(1);
  }

  @Test
  void aPayoutRunTakesTwoPeopleAndPaysTheBalance() throws Exception {
    bookings.complete(UUID.fromString(paidBooking(seeker())), Instant.now());
    await().atMost(Duration.ofSeconds(10)).until(() -> available() > 0);
    long due = available();

    var period = PayoutRuns.Period.of(LocalDate.now(DOUALA).plusDays(16));
    UUID runId =
        payoutRuns.draft(new PayoutRuns.Period(period.start().minusYears(5), period.end()));
    JsonNode run = json(mvc.perform(get("/admin/v1/payout-runs/" + runId).with(finance)));
    assertThat(run.path("status").asText()).isEqualTo("DRAFT");
    JsonNode item = itemOf(run, specialist.id());
    assertThat(item.path("status").asText()).isEqualTo("READY");
    assertThat(item.path("net").asLong()).isEqualTo(due);
    assertThat(item.path("name").asText()).isEqualTo("Pay Keeper");

    mvc.perform(post("/admin/v1/payout-runs/" + runId + "/approval").with(finance2))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("payout-run.not-submitted"));
    mvc.perform(post("/admin/v1/payout-runs/" + runId + "/submission").with(finance))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preparedBy").value("A. Fon"))
        .andExpect(jsonPath("$.canApprove").value(false));
    mvc.perform(post("/admin/v1/payout-runs/" + runId + "/approval").with(finance))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("money.same-person"));
    mvc.perform(post("/admin/v1/payout-runs/" + runId + "/approval").with(support))
        .andExpect(status().isForbidden());

    mvc.perform(post("/admin/v1/payout-runs/" + runId + "/approval").with(finance2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvedBy").value("B. Tabi"));
    payoutRuns.poll();
    JsonNode done = json(mvc.perform(get("/admin/v1/payout-runs/" + runId).with(finance)));
    assertThat(itemOf(done, specialist.id()).path("status").asText()).isEqualTo("PAID");
    assertThat(available()).isZero();

    String report =
        mvc.perform(get("/admin/v1/payout-runs/" + runId + "/report").with(finance))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(report)
        .startsWith("specialist,sessions,gross,commission,net,method,status,reason\n");
    assertThat(report).contains("\"Pay Keeper\",").contains("," + due + ",").contains("\"PAID\"");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'payments.payout-run.export' AND target_id = ?",
                Integer.class,
                runId.toString()))
        .isEqualTo(1);
    assertThat(AdminPaymentsController.cell("=SUM(A1)")).isEqualTo("\"'=SUM(A1)\"");
    mvc.perform(get("/v1/me/earnings").headers(bearer(specialist.token())))
        .andExpect(jsonPath("$.activity[0].kind").value("PAYOUT"))
        .andExpect(jsonPath("$.activity[0].amount").value(-due));
  }

  @Test
  void theLedgerOnlyGrowsAndAlwaysBalances() {
    assertThatThrownBy(() -> jdbc.update("UPDATE ledger_line SET debit = debit + 1"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    s -> {
                      UUID journal = UUID.randomUUID();
                      jdbc.update(
                          "INSERT INTO ledger_journal (id, kind, created_at) VALUES (?, 'TEST', now())",
                          journal);
                      jdbc.update(
                          "INSERT INTO ledger_line (journal_id, account, debit) VALUES (?, 'HELD', 5)",
                          journal);
                    }))
        // Refused at commit, by the deferred check.
        .hasStackTraceContaining("does not balance");
  }

  @Test
  void onlyStaffWithPaymentsSeeTheSummary() throws Exception {
    mvc.perform(get("/admin/v1/payments/summary").with(staff("cr", "C", "CRISIS_RESPONDER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/v1/payments/summary").with(support)).andExpect(status().isOk());
    mvc.perform(get("/admin/v1/payments").with(finance))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].phone").value(org.hamcrest.Matchers.containsString("••")));
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private long available() {
    return ledger.balance(
        Ledger.Account.SPECIALIST_PAYABLE, UUID.fromString(specialist.id()), null);
  }

  private String seeker() throws Exception {
    String phone = "+2376702" + String.format("%05d", PHONES.incrementAndGet());
    mvc.perform(
            post("/v1/signups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "displayName", "Payer",
                            "phone", phone,
                            "dateOfBirth", "1990-01-01",
                            "termsVersion", "v1",
                            "privacyNoticeVersion", "v1",
                            "appLanguage", "en"))))
        .andExpect(status().isAccepted());
    String token = signIn(phone);
    mvc.perform(get("/v1/me").headers(bearer(token))).andExpect(status().isOk());
    return token;
  }

  /** Books the next free Monday at 10:00 Douala time, in a week no other test uses. */
  private String book(String seeker) throws Exception {
    LocalDate day = monday.plusWeeks(WEEKS.incrementAndGet());
    String start = day.atTime(10, 0).atZone(DOUALA).toInstant().toString();
    String holdId =
        json(mvc.perform(
                    post("/v1/slot-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"specialistId\":\"%s\",\"start\":\"%s\",\"modality\":\"AUDIO\"}"
                                .formatted(specialist.id(), start)))
                .andExpect(status().isCreated()))
            .path("holdId")
            .asText();
    return json(mvc.perform(
                post("/v1/bookings")
                    .headers(bearer(seeker))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"holdId\":\"%s\",\"sessionLanguage\":\"en\"}".formatted(holdId)))
            .andExpect(status().isCreated()))
        .path("id")
        .asText();
  }

  /** Books and pays with MTN MoMo on the seeker's own number; the sandbox approves at once. */
  private String paidBooking(String seeker) throws Exception {
    String bookingId = book(seeker);
    mvc.perform(
            post("/v1/bookings/" + bookingId + "/payments")
                .headers(bearer(seeker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"MTN_MOMO\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(15000));
    mvc.perform(
            get("/v1/bookings/" + bookingId + "/payment")
                .headers(bearer(seeker))
                .param("check", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETE"));
    return bookingId;
  }

  private JsonNode proposedFor(String bookingId) throws Exception {
    for (JsonNode r :
        json(mvc.perform(get("/admin/v1/refunds").with(finance).param("status", "PROPOSED")))) {
      if (r.path("bookingId").asText().equals(bookingId)) {
        return r;
      }
    }
    return null;
  }

  private ResultActions webhook(byte[] body, String signature) throws Exception {
    var request =
        post("/v1/webhooks/payments/sandbox").contentType(MediaType.APPLICATION_JSON).content(body);
    if (signature != null) {
      request.header("X-Notch-Signature", signature);
    }
    return mvc.perform(request);
  }

  private static JsonNode itemOf(JsonNode run, String specialistId) {
    for (JsonNode i : run.path("items")) {
      if (i.path("specialistId").asText().equals(specialistId)) {
        return i;
      }
    }
    throw new AssertionError("no item for " + specialistId);
  }
}
