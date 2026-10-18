package care.solace.core.payments;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.core.specialists.SpecialistOffers;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payments, refunds and payout runs for staff (admin screens 11 and 12; tag admin-payments). Two
 * people for every refund and every payout run (D14, D35): the database refuses the same person in
 * both places too. Every change is audited.
 */
@RestController
class AdminPaymentsController {

  private static final String AREA = "payments";

  private final StaffAccess access;
  private final Payments payments;
  private final PayoutRuns runs;
  private final PaymentStore store;
  private final PayoutStore payouts;
  private final Ledger ledger;
  private final Reconciliation reconciliation;
  private final SpecialistOffers specialists;
  private final AuditLog audit;

  AdminPaymentsController(
      StaffAccess access,
      Payments payments,
      PayoutRuns runs,
      PaymentStore store,
      PayoutStore payouts,
      Ledger ledger,
      Reconciliation reconciliation,
      SpecialistOffers specialists,
      AuditLog audit) {
    this.access = access;
    this.payments = payments;
    this.runs = runs;
    this.store = store;
    this.payouts = payouts;
    this.ledger = ledger;
    this.reconciliation = reconciliation;
    this.specialists = specialists;
    this.audit = audit;
  }

  // --- Views ------------------------------------------------------------------------------------

  record ReconciliationHead(LocalDate day, int mismatches) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Summary(
      long held,
      long released,
      int refundsToApprove,
      int failures,
      ReconciliationHead lastReconciliation) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Transaction(
      UUID id,
      UUID bookingId,
      String method,
      long amount,
      String status,
      String phone,
      String providerRef,
      Instant createdAt,
      Instant completedAt) {}

  record ProposedBy(boolean system, String name) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Refund(
      UUID id,
      UUID bookingId,
      long amount,
      String method,
      String phone,
      String reason,
      Integer policyVersion,
      ProposedBy proposedBy,
      String decidedBy,
      String status,
      String failureReason,
      Instant createdAt,
      boolean canDecide) {}

  record Totals(
      int specialists, int ready, int held, int sessions, long gross, long commission, long net) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      UUID id,
      UUID specialistId,
      String name,
      int sessions,
      long gross,
      long commission,
      long net,
      String method,
      String status,
      String holdReason,
      String failureReason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Run(
      UUID id,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      String preparedBy,
      Instant preparedAt,
      String approvedBy,
      Instant approvedAt,
      BigDecimal feePercent,
      Totals totals,
      boolean canSubmit,
      boolean canApprove,
      List<Item> items) {}

  record Mismatch(String kind, UUID reference, String ours, String theirs) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ReconciliationView(LocalDate day, Integer checked, List<Mismatch> mismatches) {}

  record RefundProposal(@Min(1) long amount, @NotBlank @Size(max = 500) String reason) {}

  record Reason(@NotBlank @Size(max = 1000) String reason) {}

  // --- Payments ---------------------------------------------------------------------------------

  @GetMapping("/admin/v1/payments/summary")
  Summary summary(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    int failures =
        store.count("SELECT count(*) FROM refund WHERE status = 'FAILED'")
            + store.count(
                "SELECT count(*) FROM payout_item WHERE status = 'FAILED'"
                    + " AND updated_at > now() - interval '30 days'");
    return new Summary(
        ledger.balance(Ledger.Account.HELD, null, null),
        ledger.balance(Ledger.Account.SPECIALIST_PAYABLE, null, null),
        store.count("SELECT count(*) FROM refund WHERE status = 'PROPOSED'"),
        failures,
        reconciliation
            .latest()
            .map(r -> new ReconciliationHead(r.day(), r.mismatches().size()))
            .orElse(null));
  }

  @GetMapping("/admin/v1/payments")
  List<Transaction> payments(
      @AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status) {
    access.require(token, AREA, Level.VIEW);
    return store.payments(status, 200).stream()
        .map(
            p ->
                new Transaction(
                    p.id(),
                    p.bookingId(),
                    p.method(),
                    p.amount(),
                    p.status(),
                    mask(p.phone()),
                    p.providerRef(),
                    p.createdAt(),
                    p.completedAt()))
        .toList();
  }

  @GetMapping("/admin/v1/reconciliation")
  ReconciliationView reconciliation(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return reconciliation
        .latest()
        .map(
            r ->
                new ReconciliationView(
                    r.day(),
                    r.checked(),
                    r.mismatches().stream()
                        .map(m -> new Mismatch(m.kind(), m.reference(), m.ours(), m.theirs()))
                        .toList()))
        .orElse(new ReconciliationView(null, null, List.of()));
  }

  // --- Refunds ----------------------------------------------------------------------------------

  @GetMapping("/admin/v1/refunds")
  List<Refund> refunds(
      @AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status) {
    var staff = access.require(token, AREA, Level.VIEW);
    return store.refunds(status, 200).stream().map(r -> view(r, staff)).toList();
  }

  @PostMapping("/admin/v1/bookings/{bookingId}/refunds")
  @ResponseStatus(HttpStatus.CREATED)
  Refund propose(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID bookingId,
      @Valid @RequestBody RefundProposal body) {
    var staff = access.require(token, "bookings", Level.CHANGE);
    var refund =
        payments.propose(
            bookingId,
            body.amount(),
            body.reason(),
            new Payments.Decider(staff.id(), staff.name()));
    audit.adminAction(
        "payments.refund.propose",
        "booking",
        bookingId.toString(),
        Map.of("refund", refund.id().toString()));
    return view(refund, staff);
  }

  @PostMapping("/admin/v1/refunds/{refundId}/approval")
  Refund approve(@AuthenticationPrincipal Jwt token, @PathVariable UUID refundId) {
    var staff = finance(token);
    var refund = payments.approve(refundId, new Payments.Decider(staff.id(), staff.name()));
    audit.adminAction("payments.refund.approve", "refund", refundId.toString(), Map.of());
    return view(refund, staff);
  }

  @PostMapping("/admin/v1/refunds/{refundId}/decline")
  Refund decline(@AuthenticationPrincipal Jwt token, @PathVariable UUID refundId) {
    var staff = finance(token);
    var refund = payments.decline(refundId, new Payments.Decider(staff.id(), staff.name()));
    audit.adminAction("payments.refund.decline", "refund", refundId.toString(), Map.of());
    return view(refund, staff);
  }

  @PostMapping("/admin/v1/refunds/{refundId}/retry")
  Refund retry(@AuthenticationPrincipal Jwt token, @PathVariable UUID refundId) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var refund = payments.retry(refundId);
    audit.adminAction("payments.refund.retry", "refund", refundId.toString(), Map.of());
    return view(refund, staff);
  }

  // --- Payout runs ------------------------------------------------------------------------------

  @GetMapping("/admin/v1/payout-runs")
  List<Run> runs(@AuthenticationPrincipal Jwt token, Locale locale) {
    var staff = access.require(token, AREA, Level.VIEW);
    return payouts.runs(50).stream().map(r -> view(r, staff, locale, false)).toList();
  }

  @GetMapping("/admin/v1/payout-runs/{runId}")
  Run run(@AuthenticationPrincipal Jwt token, @PathVariable UUID runId, Locale locale) {
    var staff = access.require(token, AREA, Level.VIEW);
    return view(runOf(runId), staff, locale, true);
  }

  @PostMapping("/admin/v1/payout-runs/{runId}/submission")
  Run submit(@AuthenticationPrincipal Jwt token, @PathVariable UUID runId, Locale locale) {
    var staff = finance(token);
    runs.submit(runId, new Payments.Decider(staff.id(), staff.name()));
    audit.adminAction("payments.payout-run.submit", "payout-run", runId.toString(), Map.of());
    return view(runOf(runId), staff, locale, true);
  }

  @PostMapping("/admin/v1/payout-runs/{runId}/approval")
  Run approveRun(@AuthenticationPrincipal Jwt token, @PathVariable UUID runId, Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    if (!staff.roles().contains("FINANCE") && !staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    runs.approve(runId, new Payments.Decider(staff.id(), staff.name()));
    audit.adminAction("payments.payout-run.approve", "payout-run", runId.toString(), Map.of());
    return view(runOf(runId), staff, locale, true);
  }

  @PutMapping("/admin/v1/payout-runs/{runId}/items/{itemId}/hold")
  Run hold(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID runId,
      @PathVariable UUID itemId,
      @Valid @RequestBody Reason body,
      Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    runs.hold(runId, itemId, body.reason());
    audit.adminAction(
        "payments.payout-item.hold", "payout-item", itemId.toString(), Map.of("reason", "set"));
    return view(runOf(runId), staff, locale, true);
  }

  @DeleteMapping("/admin/v1/payout-runs/{runId}/items/{itemId}/hold")
  Run releaseHold(
      @AuthenticationPrincipal Jwt token,
      @PathVariable UUID runId,
      @PathVariable UUID itemId,
      Locale locale) {
    var staff = access.require(token, AREA, Level.CHANGE);
    runs.release(runId, itemId);
    audit.adminAction("payments.payout-item.release", "payout-item", itemId.toString(), Map.of());
    return view(runOf(runId), staff, locale, true);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  /** Refunds and run submissions are Finance's (D14): change access is not enough. */
  private StaffAccess.Staff finance(Jwt token) {
    var staff = access.require(token, AREA, Level.CHANGE);
    if (!staff.roles().contains("FINANCE")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return staff;
  }

  private PayoutStore.Run runOf(UUID runId) {
    return payouts.run(runId).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private Refund view(PaymentStore.Refund r, StaffAccess.Staff staff) {
    var p = store.payment(r.paymentId()).orElseThrow();
    return new Refund(
        r.id(),
        r.bookingId(),
        r.amount(),
        p.method(),
        mask(p.phone()),
        r.reason(),
        r.policyVersion(),
        new ProposedBy(r.bySystem(), r.proposedByName()),
        r.decidedByName(),
        r.status(),
        r.failureReason(),
        r.createdAt(),
        r.status().equals("PROPOSED")
            && staff.roles().contains("FINANCE")
            && !staff.id().equals(r.proposedBy()));
  }

  private Run view(PayoutStore.Run r, StaffAccess.Staff staff, Locale locale, boolean withItems) {
    var items = payouts.items(r.id());
    var ready = items.stream().filter(i -> !i.status().equals("HELD")).toList();
    var totals =
        new Totals(
            items.size(),
            ready.size(),
            items.size() - ready.size(),
            ready.stream().mapToInt(PayoutStore.Item::sessions).sum(),
            ready.stream().mapToLong(PayoutStore.Item::gross).sum(),
            ready.stream().mapToLong(PayoutStore.Item::commission).sum(),
            ready.stream().mapToLong(PayoutStore.Item::net).sum());
    boolean finance = staff.roles().contains("FINANCE");
    return new Run(
        r.id(),
        r.periodStart(),
        r.periodEnd(),
        r.status(),
        r.preparedByName(),
        r.preparedAt(),
        r.approvedByName(),
        r.approvedAt(),
        r.feePercent(),
        totals,
        r.status().equals("DRAFT") && finance,
        r.status().equals("SUBMITTED")
            && (finance || staff.roles().contains("SUPER_ADMIN"))
            && !staff.id().equals(r.preparedBy()),
        withItems
            ? items.stream()
                .map(
                    i ->
                        new Item(
                            i.id(),
                            i.specialistId(),
                            specialists
                                .summary(i.specialistId(), locale)
                                .map(SpecialistOffers.Summary::fullName)
                                .orElse(""),
                            i.sessions(),
                            i.gross(),
                            i.commission(),
                            i.net(),
                            i.method(),
                            i.status(),
                            i.holdReason(),
                            i.failureReason()))
                .toList()
            : null);
  }

  /** "+237 6•• •• •• 52", as the Payments screen shows a wallet. */
  static String mask(String phone) {
    if (phone == null || phone.length() < 6) {
      return "••";
    }
    if (phone.startsWith("+237") && phone.length() == 13) {
      return "+237 " + phone.charAt(4) + "•• •• •• " + phone.substring(11);
    }
    return phone.substring(0, 4) + " •• " + phone.substring(phone.length() - 2);
  }
}
