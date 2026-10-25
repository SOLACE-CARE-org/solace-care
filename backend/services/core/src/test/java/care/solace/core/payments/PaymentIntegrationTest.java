package care.solace.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
  @Autowired PaymentProviders providers;

  private final RequestPostProcessor finance = staff("fin-a", "A. Fon", "FINANCE");
  private final RequestPostProcessor finance2 = staff("fin-b", "B. Tabi", "FINANCE");
  private final RequestPostProcessor support = staff("sup-a", "E. Nkeng", "SUPPORT");
  private final RequestPostProcessor ops = staff("ops-p", "R", "OPERATIONS");
  private final RequestPostProcessor superAdmin = staff("sa-p", "P. Ngu", "SUPER_ADMIN");
  private static final AtomicInteger PROVIDERS = new AtomicInteger();

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
  void supportResolvesADisputeAndADifferentFinanceMemberApprovesTheRefund() throws Exception {
    String bookingId = paidBooking(seeker());
    bookings.complete(UUID.fromString(bookingId), Instant.now());
    String reference = "BK-" + bookingId.replace("-", "").substring(0, 8).toUpperCase();

    mvc.perform(get("/admin/v1/bookings").with(support).param("q", reference))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].reference").value(reference))
        .andExpect(jsonPath("$[0].personRef").value(org.hamcrest.Matchers.startsWith("#")))
        .andExpect(jsonPath("$[0].disputed").value(false));

    // Nothing to resolve before a dispute is opened.
    resolve(bookingId, "{\"outcome\":\"SPECIALIST_NO_SHOW\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("booking.not-disputed"));
    mvc.perform(
            post("/admin/v1/bookings/" + bookingId + "/disputes")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Seeker says the specialist never came.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.disputed").value(true))
        .andExpect(jsonPath("$.dispute.openedBy").value("E. Nkeng"));
    mvc.perform(get("/admin/v1/bookings").with(support).param("status", "DISPUTED"))
        .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(bookingId)).exists());

    // Changing the policy amount needs a reason.
    resolve(bookingId, "{\"outcome\":\"SPECIALIST_NO_SHOW\",\"amount\":5000}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("booking.reason-needed"));
    resolve(bookingId, "{\"outcome\":\"SPECIALIST_NO_SHOW\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.disputed").value(false))
        .andExpect(jsonPath("$.dispute.outcome").value("SPECIALIST_NO_SHOW"))
        .andExpect(jsonPath("$.timeline[?(@.kind == 'DISPUTE_RESOLVED')]").exists());

    JsonNode refund = proposedFor(bookingId);
    assertThat(refund).isNotNull();
    assertThat(refund.path("proposedBy").path("name").asText()).isEqualTo("E. Nkeng");
    mvc.perform(post("/admin/v1/refunds/" + refund.path("id").asText() + "/approval").with(finance))
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action LIKE 'bookings.dispute.%' AND target_id = ?",
                Integer.class, bookingId))
        .isEqualTo(2);
  }

  @Test
  void bookingsCannotBeChangedWithViewAccessAndExportsAreAudited() throws Exception {
    String bookingId = paidBooking(seeker());
    mvc.perform(
            post("/admin/v1/bookings/" + bookingId + "/disputes")
                .with(finance)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/v1/bookings/" + bookingId).with(staff("cs", "C", "CONTENT_EDITOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/v1/bookings").with(support).param("q", "not hex"))
        .andExpect(status().isBadRequest());
    String csv =
        mvc.perform(get("/admin/v1/bookings/export").with(support))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("reference,starts_at");
    assertThat(csv).doesNotContain("+237");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'bookings.export'", Integer.class))
        .isPositive();

    // Support cancels an upcoming booking for the seeker: the full price is proposed back.
    mvc.perform(
            post("/admin/v1/bookings/" + bookingId + "/cancellation")
                .with(support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Seeker called, cannot make it.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledBy").value("STAFF"))
        .andExpect(jsonPath("$.canCancel").value(false));
    JsonNode refund =
        await().atMost(Duration.ofSeconds(10)).until(() -> proposedFor(bookingId), r -> r != null);
    assertThat(refund.path("amount").asLong()).isEqualTo(15000);
    // Settle it, so the specialist's payout is not held for other tests.
    mvc.perform(post("/admin/v1/refunds/" + refund.path("id").asText() + "/approval").with(finance))
        .andExpect(status().isOk());
  }

  private ResultActions resolve(String bookingId, String body) throws Exception {
    return mvc.perform(
        post("/admin/v1/bookings/" + bookingId + "/dispute-resolution")
            .with(support)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
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

  // --- Payment providers as rows (D61) ---------------------------------------------------------

  @Test
  void aRoutedProviderTakesTheChargeAndKeepsWhatItStarted() throws Exception {
    String second = activeSandbox();
    JsonNode before = routes();
    try {
      putRoutes(withFirst(before, "COLLECT", "MTN_MOMO", second), "Second account first");
      long atSecond = clearing(second);
      String bookingId = paidBooking(seeker());
      assertThat(
              jdbc.queryForObject(
                  "SELECT provider FROM payment WHERE booking_id = ?::uuid AND status = 'COMPLETE'",
                  String.class,
                  bookingId))
          .isEqualTo(second);
      // The capture sits in the second provider's float.
      assertThat(clearing(second)).isEqualTo(atSecond + 15000);
      mvc.perform(get("/admin/v1/payments").with(finance))
          .andExpect(jsonPath("$[0].provider").value(second));
    } finally {
      putRoutes(before, "Back to the sandbox");
    }
  }

  @Test
  void aWebhookOnlyCountsForThePaymentsOfItsOwnProvider() throws Exception {
    String second = activeSandbox();
    JsonNode before = routes();
    try {
      putRoutes(withFirst(before, "COLLECT", "ORANGE_MONEY", second), "Second account first");
      String seeker = seeker();
      String bookingId = book(seeker);
      String paymentId =
          json(mvc.perform(
                      post("/v1/bookings/" + bookingId + "/payments")
                          .headers(bearer(seeker))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"method\":\"ORANGE_MONEY\"}"))
                  .andExpect(status().isCreated()))
              .path("id")
              .asText();
      String ref =
          jdbc.queryForObject(
              "SELECT provider_ref FROM payment WHERE id = ?::uuid", String.class, paymentId);
      byte[] body =
          ("{\"id\":\"evt-"
                  + UUID.randomUUID()
                  + "\",\"type\":\"payment.complete\",\"data\":{\"reference\":\""
                  + ref
                  + "\"}}")
              .getBytes(StandardCharsets.UTF_8);
      String signature = NotchPayFormat.sign("sandbox-webhook-secret", body);
      // Signed, but sent to the other provider's path: the payment is not that provider's.
      webhook("sandbox", body, signature).andExpect(status().isNoContent());
      assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
          .isEqualTo("PENDING_PAYMENT");
      webhook(second, body, signature).andExpect(status().isNoContent());
      assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
          .isEqualTo("CONFIRMED");
      // A provider that does not serve takes no webhooks at all.
      webhook("nobody", body, signature).andExpect(status().isUnauthorized());
    } finally {
      putRoutes(before, "Back to the sandbox");
    }
  }

  @Test
  void aRefundGoesBackThroughTheProviderThatTookItWhileItIsActive() throws Exception {
    String second = activeSandbox();
    JsonNode before = routes();
    try {
      putRoutes(withFirst(before, "COLLECT", "MTN_MOMO", second), "Second account first");
      String active = refundedThrough(seeker());
      assertThat(active).isEqualTo(second);

      String drained = paidBooking(seeker());
      provider(second, "DRAINING").andExpect(status().isOk());
      refundAndApprove(drained);
      // Draining: the REFUND route's first active provider pays it instead.
      assertThat(refundProvider(drained)).isEqualTo("sandbox");
      payments.poll();
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM refund WHERE booking_id = ?::uuid", String.class, drained))
          .isEqualTo("PAID");
    } finally {
      provider(second, "ACTIVE");
      putRoutes(before, "Back to the sandbox");
    }
  }

  @Test
  void aProviderIsDisabledOnlyWhenNothingIsOnItsWay() throws Exception {
    String second = activeSandbox();
    JsonNode before = routes();
    String seeker = seeker();
    String bookingId = book(seeker);
    try {
      putRoutes(withFirst(before, "COLLECT", "MTN_MOMO", second), "Second account first");
      mvc.perform(
              post("/v1/bookings/" + bookingId + "/payments")
                  .headers(bearer(seeker))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"method\":\"MTN_MOMO\"}"))
          .andExpect(status().isCreated());
      putRoutes(before, "Back to the sandbox");
      provider(second, "DRAINING").andExpect(status().isOk());
      provider(second, "DISABLED")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("payment-provider.in-flight"));
      // Draining still checks what it holds; then nothing is left.
      payments.poll();
      assertThat(bookings.find(UUID.fromString(bookingId)).orElseThrow().status())
          .isEqualTo("CONFIRMED");
      provider(second, "DISABLED").andExpect(status().isOk());
    } finally {
      putRoutes(before, "Back to the sandbox");
    }
  }

  @Test
  void withRoutingOnAChargeThatNeverReachedAProviderTriesTheNext() throws Exception {
    String broken = "np-down-" + PROVIDERS.incrementAndGet();
    // Nothing listens on port 1: the request never reaches a provider. Made ACTIVE in the
    // database, since it would never pass the connection test.
    jdbc.update(
        """
        INSERT INTO payment_provider (code, adapter, display_name, mode, status, base_url,
                                      secret_ref, version, updated_by, updated_by_name, updated_at)
        VALUES (?, 'NOTCHPAY', 'Down', 'TEST', 'ACTIVE', 'https://127.0.0.1:1', 'config', 1,
                'test', 'Test', now())
        """,
        broken);
    providers.reload();
    JsonNode before = routes();
    try {
      putRoutes(withFirst(before, "COLLECT", "MTN_MOMO", broken), "Down first");
      flag("paymentRouting", true);
      String bookingId = paidBooking(seeker());
      assertThat(
              jdbc.queryForList(
                  "SELECT provider || ':' || status FROM payment WHERE booking_id = ?::uuid"
                      + " ORDER BY created_at",
                  String.class,
                  bookingId))
          .containsExactly(broken + ":FAILED", "sandbox:COMPLETE");

      // Off, the first provider is the only one asked.
      flag("paymentRouting", false);
      String seeker = seeker();
      String other = book(seeker);
      mvc.perform(
              post("/v1/bookings/" + other + "/payments")
                  .headers(bearer(seeker))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"method\":\"MTN_MOMO\"}"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      flag("paymentRouting", false);
      putRoutes(before, "Back to the sandbox");
      jdbc.update("UPDATE payment_provider SET status = 'DISABLED' WHERE code = ?", broken);
      providers.reload();
    }
  }

  @Test
  void onlyASuperAdminChangesProvidersAndTheRulesHold() throws Exception {
    mvc.perform(get("/admin/v1/payment-providers").with(finance))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.code == 'sandbox')].keysFound").value(true))
        .andExpect(
            jsonPath("$[?(@.code == 'sandbox')].webhookPath")
                .value("/v1/webhooks/payments/sandbox"));
    String body =
        "{\"code\":\"np-live-%d\",\"adapter\":\"%s\",\"displayName\":\"Notch Pay 2\",\"mode\":\"%s\",\"secretRef\":\"%s\",\"reason\":\"Second account\"}";
    mvc.perform(
            post("/admin/v1/payment-providers")
                .with(finance)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(1, "NOTCHPAY", "LIVE", "np-two")))
        .andExpect(status().isForbidden());
    // A sandbox never takes real money.
    mvc.perform(
            post("/admin/v1/payment-providers")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(2, "SANDBOX", "LIVE", "config")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("payment-provider.invalid"));
    // Keys are never accepted as a reference to a path.
    mvc.perform(
            post("/admin/v1/payment-providers")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(3, "NOTCHPAY", "LIVE", "../etc")))
        .andExpect(status().isBadRequest());

    int n = PROVIDERS.incrementAndGet() + 100;
    String code = "np-live-" + n;
    mvc.perform(
            post("/admin/v1/payment-providers")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(n, "NOTCHPAY", "LIVE", "np-missing")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.keysFound").value(false));
    String activate =
        "{\"version\":1,\"displayName\":\"Notch Pay 2\",\"mode\":\"LIVE\",\"status\":\"ACTIVE\",\"secretRef\":\"np-missing\",%s\"reason\":\"Go live\"}";
    String allChecks =
        "\"checks\":{\"recordsOfProcessing\":true,\"threatModel\":true,\"contract\":true,\"testTransactions\":true},";
    // LIVE needs every activation check, then keys and a passing test.
    putProvider(code, activate.formatted(""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("payment-provider.checks-missing"));
    putProvider(code, activate.formatted(allChecks))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("payment-provider.not-ready"));
    mvc.perform(post("/admin/v1/payment-providers/" + code + "/test").with(superAdmin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.keysFound").value(false));

    // Routes always leave a way to collect, refund and pay out.
    JsonNode routes = routes();
    mvc.perform(
            put("/admin/v1/payment-routes")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "version", routes.path("version").asInt(),
                            "routes", java.util.List.of(),
                            "reason", "Empty"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("payment-route.invalid"));
    mvc.perform(
            put("/admin/v1/payment-routes")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "version",
                            routes.path("version").asInt() - 1,
                            "routes",
                            routes.path("routes"),
                            "reason",
                            "Stale"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("payment-route.changed"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'payments.provider.create'"
                    + " AND target_id = ?",
                Integer.class,
                code))
        .isEqualTo(1);
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

  /** A second sandbox account, ACTIVE, with the sandbox's own keys. */
  private String activeSandbox() throws Exception {
    String code = "sbx-" + PROVIDERS.incrementAndGet();
    mvc.perform(
            post("/admin/v1/payment-providers")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    ("{\"code\":\"%s\",\"adapter\":\"SANDBOX\",\"displayName\":\"Sandbox two\","
                            + "\"mode\":\"TEST\",\"secretRef\":\"config\",\"reason\":\"Test\"}")
                        .formatted(code)))
        .andExpect(status().isCreated());
    provider(code, "ACTIVE").andExpect(status().isOk());
    return code;
  }

  private ResultActions provider(String code, String status) throws Exception {
    JsonNode row = null;
    for (JsonNode p : json(mvc.perform(get("/admin/v1/payment-providers").with(superAdmin)))) {
      if (p.path("code").asText().equals(code)) {
        row = p;
      }
    }
    assertThat(row).as(code).isNotNull();
    return putProvider(
        code,
        ("{\"version\":%d,\"displayName\":\"%s\",\"mode\":\"TEST\",\"status\":\"%s\","
                + "\"secretRef\":\"config\",\"reason\":\"Test\"}")
            .formatted(row.path("version").asInt(), row.path("displayName").asText(), status));
  }

  private ResultActions putProvider(String code, String body) throws Exception {
    return mvc.perform(
        put("/admin/v1/payment-providers/" + code)
            .with(superAdmin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private JsonNode routes() throws Exception {
    return json(mvc.perform(get("/admin/v1/payment-routes").with(superAdmin)));
  }

  /** The routes with {@code code} first for one purpose and method, the rest after it. */
  private static JsonNode withFirst(JsonNode routes, String purpose, String method, String code) {
    var copy = routes.deepCopy();
    var list = (com.fasterxml.jackson.databind.node.ArrayNode) copy.path("routes");
    for (JsonNode r : list) {
      if (r.path("purpose").asText().equals(purpose) && r.path("method").asText().equals(method)) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) r)
            .put("priority", r.path("priority").asInt() + 1);
      }
    }
    list.addObject()
        .put("purpose", purpose)
        .put("method", method)
        .put("priority", 1)
        .put("providerCode", code);
    return copy;
  }

  private void putRoutes(JsonNode routes, String reason) throws Exception {
    var current = routes();
    mvc.perform(
            put("/admin/v1/payment-routes")
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "version", current.path("version").asInt(),
                            "routes", routes.path("routes"),
                            "reason", reason))))
        .andExpect(status().isOk());
  }

  private void flag(String key, boolean enabled) throws Exception {
    mvc.perform(
            put("/admin/v1/flags/" + key)
                .with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":%s,\"reason\":\"Test\"}".formatted(enabled)))
        .andExpect(status().isOk());
  }

  private long clearing(String code) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(debit) - sum(credit), 0) FROM ledger_line"
            + " WHERE account = 'PROVIDER_CLEARING' AND provider = ?",
        Long.class,
        code);
  }

  /** Pays, then refunds through Finance; returns the provider the refund was sent through. */
  private String refundedThrough(String seeker) throws Exception {
    String bookingId = paidBooking(seeker);
    refundAndApprove(bookingId);
    return refundProvider(bookingId);
  }

  /** Support proposes a full refund and Finance approves it, so it is sent at once. */
  private void refundAndApprove(String bookingId) throws Exception {
    payments.propose(
        UUID.fromString(bookingId), 15000, "Booked twice", new Payments.Decider("sup-x", "X"));
    String refundId =
        jdbc.queryForObject(
            "SELECT id::text FROM refund WHERE booking_id = ?::uuid AND status = 'PROPOSED'",
            String.class,
            bookingId);
    mvc.perform(post("/admin/v1/refunds/" + refundId + "/approval").with(finance))
        .andExpect(status().isOk());
  }

  private String refundProvider(String bookingId) {
    return jdbc.queryForObject(
        "SELECT provider FROM refund WHERE booking_id = ?::uuid", String.class, bookingId);
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
    return webhook("sandbox", body, signature);
  }

  private ResultActions webhook(String provider, byte[] body, String signature) throws Exception {
    var request =
        post("/v1/webhooks/payments/" + provider)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
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
