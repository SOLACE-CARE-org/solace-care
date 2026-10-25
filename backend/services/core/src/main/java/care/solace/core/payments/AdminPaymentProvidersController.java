package care.solace.core.payments;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment providers and routes for staff (tag admin-payments; D61). Anyone with payments access
 * sees them; only a Super admin changes them, each change with a reason and audited. Keys are never
 * read or shown, only whether they are found.
 */
@RestController
class AdminPaymentProvidersController {

  private static final String AREA = "payments";
  private static final String CODE = "^[a-z][a-z0-9-]{1,30}$";
  private static final String SECRET_REF = "^[a-z0-9][a-z0-9._-]{0,62}$";

  private final StaffAccess access;
  private final ProviderAdmin admin;
  private final ProviderStore store;
  private final PaymentProviders providers;
  private final AuditLog audit;

  AdminPaymentProvidersController(
      StaffAccess access,
      ProviderAdmin admin,
      ProviderStore store,
      PaymentProviders providers,
      AuditLog audit) {
    this.access = access;
    this.admin = admin;
    this.store = store;
    this.providers = providers;
    this.audit = audit;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Account(
      String code,
      ProviderStore.Adapter adapter,
      String displayName,
      ProviderStore.Mode mode,
      ProviderStore.Status status,
      String baseUrl,
      String secretRef,
      int version,
      String updatedBy,
      Instant updatedAt,
      boolean keysFound,
      String circuit,
      int inFlight,
      long clearing,
      String webhookPath) {}

  record Draft(
      @NotBlank @Pattern(regexp = CODE) String code,
      @NotNull ProviderStore.Adapter adapter,
      @NotBlank @Size(max = 80) String displayName,
      @NotNull ProviderStore.Mode mode,
      @Size(max = 200) String baseUrl,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      @NotBlank @Size(max = 500) String reason) {}

  record Checks(
      boolean recordsOfProcessing,
      boolean threatModel,
      boolean contract,
      boolean testTransactions) {}

  record Change(
      @Min(1) int version,
      @NotBlank @Size(max = 80) String displayName,
      @NotNull ProviderStore.Mode mode,
      @NotNull ProviderStore.Status status,
      @Size(max = 200) String baseUrl,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      Checks checks,
      @NotBlank @Size(max = 500) String reason) {}

  record Route(
      @NotNull ProviderStore.Purpose purpose,
      @NotNull PaymentProvider.Method method,
      @Min(1) @Max(10) int priority,
      @NotBlank @Pattern(regexp = CODE) String providerCode,
      @Min(1) Long minAmount,
      @Min(1) Long maxAmount) {}

  record Routes(int version, List<Route> routes) {}

  record RoutesChange(
      @Min(0) int version,
      @NotNull @Size(max = 60) List<@Valid Route> routes,
      @NotBlank @Size(max = 500) String reason) {}

  record Version(
      int version,
      Map<String, Object> snapshot,
      String reason,
      String changedBy,
      Instant changedAt) {}

  // --- Providers --------------------------------------------------------------------------------

  @GetMapping("/admin/v1/payment-providers")
  List<Account> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return store.providers().stream().map(this::view).toList();
  }

  @PostMapping("/admin/v1/payment-providers")
  @ResponseStatus(HttpStatus.CREATED)
  Account create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Draft body) {
    var by = superAdmin(token);
    var row =
        admin.create(
            new ProviderAdmin.Draft(
                body.code(),
                body.adapter(),
                body.displayName(),
                body.mode(),
                body.baseUrl(),
                body.secretRef()),
            body.reason(),
            by);
    audit.adminAction(
        "payments.provider.create",
        "payment_provider",
        row.code(),
        body.reason().strip(),
        Map.of("adapter", row.adapter().name(), "mode", row.mode().name()));
    return view(row);
  }

  @PutMapping("/admin/v1/payment-providers/{code}")
  Account change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody Change body) {
    var by = superAdmin(token);
    var checks =
        body.checks() == null
            ? null
            : new ProviderAdmin.Checks(
                body.checks().recordsOfProcessing(),
                body.checks().threatModel(),
                body.checks().contract(),
                body.checks().testTransactions());
    var row =
        admin.change(
            code,
            new ProviderAdmin.Change(
                body.version(),
                body.displayName(),
                body.mode(),
                body.status(),
                body.baseUrl(),
                body.secretRef(),
                checks),
            body.reason(),
            by);
    audit.adminAction(
        "payments.provider.change",
        "payment_provider",
        code,
        body.reason().strip(),
        Map.of(
            "status", row.status().name(),
            "mode", row.mode().name(),
            "version", String.valueOf(row.version())));
    return view(row);
  }

  @PostMapping("/admin/v1/payment-providers/{code}/test")
  ProviderAdmin.Test test(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    superAdmin(token);
    var result = admin.test(admin.provider(code));
    audit.adminAction(
        "payments.provider.test",
        "payment_provider",
        code,
        Map.of("ok", String.valueOf(result.ok())));
    return result;
  }

  @GetMapping("/admin/v1/payment-providers/{code}/history")
  List<Version> history(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    access.require(token, AREA, Level.VIEW);
    admin.provider(code);
    return store.versions(code).stream()
        .map(v -> new Version(v.version(), v.snapshot(), v.reason(), v.by(), v.at()))
        .toList();
  }

  // --- Routes -----------------------------------------------------------------------------------

  @GetMapping("/admin/v1/payment-routes")
  Routes routes(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return routes();
  }

  @PutMapping("/admin/v1/payment-routes")
  Routes changeRoutes(@AuthenticationPrincipal Jwt token, @Valid @RequestBody RoutesChange body) {
    var by = superAdmin(token);
    admin.replaceRoutes(
        body.routes().stream()
            .map(
                r ->
                    new ProviderStore.Route(
                        r.purpose(),
                        r.method(),
                        r.priority(),
                        r.providerCode(),
                        r.minAmount(),
                        r.maxAmount()))
            .toList(),
        body.version(),
        body.reason(),
        by);
    var after = routes();
    audit.adminAction(
        "payments.routes.change",
        "payment_route",
        String.valueOf(after.version()),
        body.reason().strip(),
        Map.of("routes", String.valueOf(after.routes().size())));
    return after;
  }

  private Routes routes() {
    return new Routes(
        store.routeVersion(),
        store.routes().stream()
            .map(
                r ->
                    new Route(
                        r.purpose(),
                        r.method(),
                        r.priority(),
                        r.providerCode(),
                        r.minAmount(),
                        r.maxAmount()))
            .toList());
  }

  /** Providers and routes are changed by a Super admin only (D61). */
  private Payments.Decider superAdmin(Jwt token) {
    var staff = access.require(token, AREA, Level.VIEW);
    if (!staff.roles().contains("SUPER_ADMIN")) {
      throw new SolaceException(CommonError.ACCESS_DENIED);
    }
    return new Payments.Decider(staff.id(), staff.name());
  }

  private Account view(ProviderStore.Provider p) {
    return new Account(
        p.code(),
        p.adapter(),
        p.displayName(),
        p.mode(),
        p.status(),
        p.baseUrl(),
        p.secretRef(),
        p.version(),
        p.updatedByName(),
        p.updatedAt(),
        providers.keysFound(p),
        providers.circuit(p.code()).orElse(null),
        store.inFlight(p.code()),
        store.clearing(p.code()),
        "/v1/webhooks/payments/" + p.code());
  }
}
