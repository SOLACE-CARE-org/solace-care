package care.solace.core.notifications;

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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SMS and email senders for staff (tag admin-settings; D61). Super admins and Operations see them;
 * only a Super admin adds or changes one, with a reason, audited. Keys are never read or shown,
 * only whether they are found. A sender becomes ACTIVE only with valid settings and its keys, and
 * no change may leave a channel that had an ACTIVE sender without one: sign-in codes go by SMS.
 */
@RestController
class MessageSendersController {

  private static final String AREA = "settings";
  private static final String CODE = "^[a-z][a-z0-9-]{1,30}$";
  private static final String SECRET_REF = "^[a-z0-9][a-z0-9._-]{0,62}$";

  private final StaffAccess access;
  private final SenderStore store;
  private final MessageSenders senders;
  private final Copy copy;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  MessageSendersController(
      StaffAccess access,
      SenderStore store,
      MessageSenders senders,
      Copy copy,
      AuditLog audit,
      TransactionTemplate transactions,
      Clock clock) {
    this.access = access;
    this.store = store;
    this.senders = senders;
    this.copy = copy;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record View(
      String code,
      SenderStore.Channel channel,
      SenderStore.Adapter adapter,
      String displayName,
      SenderStore.Status status,
      int priority,
      String secretRef,
      Map<String, Object> settings,
      int version,
      String updatedBy,
      Instant updatedAt,
      boolean keysFound,
      String problem,
      String circuit) {}

  record Draft(
      @NotBlank @Pattern(regexp = CODE) String code,
      @NotNull SenderStore.Channel channel,
      @NotNull SenderStore.Adapter adapter,
      @NotBlank @Size(max = 80) String displayName,
      @Min(1) @Max(10) int priority,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      @NotNull Map<String, Object> settings,
      @NotBlank @Size(max = 500) String reason) {}

  record Change(
      @Min(1) int version,
      @NotBlank @Size(max = 80) String displayName,
      @NotNull SenderStore.Status status,
      @Min(1) @Max(10) int priority,
      @NotBlank @Pattern(regexp = SECRET_REF) String secretRef,
      @NotNull Map<String, Object> settings,
      @NotBlank @Size(max = 500) String reason) {}

  record TestRequest(@NotBlank @Size(max = 254) String to) {}

  record TestResult(boolean ok, String problem) {}

  record Version(
      int version,
      Map<String, Object> snapshot,
      String reason,
      String changedBy,
      Instant changedAt) {}

  @GetMapping("/admin/v1/message-senders")
  List<View> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    return store.all().stream().map(this::view).toList();
  }

  @PostMapping("/admin/v1/message-senders")
  @ResponseStatus(HttpStatus.CREATED)
  View create(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Draft body) {
    var by = superAdmin(token);
    var row =
        new SenderStore.Sender(
            body.code(),
            body.channel(),
            body.adapter(),
            body.displayName().strip(),
            SenderStore.Status.DRAFT,
            body.priority(),
            body.secretRef(),
            body.settings(),
            1,
            by.name(),
            clock.instant());
    fits(row);
    try {
      transactions.executeWithoutResult(
          s -> store.insert(row, body.reason().strip(), by, clock.instant()));
    } catch (DuplicateKeyException e) {
      throw new SolaceException(SenderError.EXISTS);
    }
    senders.reload();
    audit.adminAction(
        "notifications.sender.create",
        "message_sender",
        row.code(),
        body.reason().strip(),
        Map.of("channel", row.channel().name(), "adapter", row.adapter().name()));
    return view(store.find(row.code()).orElseThrow());
  }

  @PutMapping("/admin/v1/message-senders/{code}")
  View change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody Change body) {
    var by = superAdmin(token);
    var current = find(code);
    if (current.version() != body.version()) {
      throw new SolaceException(SenderError.CHANGED);
    }
    var next =
        new SenderStore.Sender(
            code,
            current.channel(),
            current.adapter(),
            body.displayName().strip(),
            body.status(),
            body.priority(),
            body.secretRef(),
            body.settings(),
            current.version(),
            by.name(),
            clock.instant());
    fits(next);
    if (next.status() == SenderStore.Status.ACTIVE
        && (senders.problem(next).isPresent() || !senders.keysFound(next))) {
      throw new SolaceException(SenderError.NOT_READY);
    }
    if (current.status() == SenderStore.Status.ACTIVE
        && next.status() != SenderStore.Status.ACTIVE
        && store.all().stream()
            .noneMatch(
                s ->
                    !s.code().equals(code)
                        && s.channel() == current.channel()
                        && s.status() == SenderStore.Status.ACTIVE)) {
      throw new SolaceException(SenderError.LAST_ACTIVE);
    }
    boolean saved =
        Boolean.TRUE.equals(
            transactions.execute(
                s ->
                    store.update(
                        next, current.version(), body.reason().strip(), by, clock.instant())));
    if (!saved) {
      throw new SolaceException(SenderError.CHANGED);
    }
    senders.reload();
    audit.adminAction(
        "notifications.sender.change",
        "message_sender",
        code,
        body.reason().strip(),
        Map.of("status", next.status().name(), "version", String.valueOf(current.version() + 1)));
    return view(find(code));
  }

  /** Sends one test message through this sender only, to a number or address staff give. */
  @PostMapping("/admin/v1/message-senders/{code}/test")
  TestResult test(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String code,
      @Valid @RequestBody TestRequest body,
      Locale locale) {
    superAdmin(token);
    var row = find(code);
    var problem = senders.problem(row);
    TestResult result;
    if (problem.isPresent()) {
      result = new TestResult(false, problem.get());
    } else {
      try {
        if (row.channel() == SenderStore.Channel.SMS) {
          senders.test(
              row, body.to().strip(), null, Sms.checked(copy.text("sms.sender-test", locale)));
        } else {
          senders.test(
              row,
              body.to().strip(),
              copy.text("email.sender-test.subject", locale),
              copy.text("email.sender-test.body", locale));
        }
        result = new TestResult(true, null);
      } catch (MessageSenders.SendFailed | IllegalArgumentException e) {
        result = new TestResult(false, e.getMessage());
      }
    }
    // The destination is not recorded: it is a person's contact.
    audit.adminAction(
        "notifications.sender.test",
        "message_sender",
        code,
        Map.of("ok", String.valueOf(result.ok())));
    return result;
  }

  @GetMapping("/admin/v1/message-senders/{code}/history")
  List<Version> history(@AuthenticationPrincipal Jwt token, @PathVariable String code) {
    access.require(token, AREA, Level.VIEW);
    find(code);
    return store.versions(code).stream()
        .map(v -> new Version(v.version(), v.snapshot(), v.reason(), v.by(), v.at()))
        .toList();
  }

  private SenderStore.Sender find(String code) {
    return store.find(code).orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  /** The adapter must suit the channel, and its settings must build. */
  private void fits(SenderStore.Sender row) {
    boolean suits =
        row.adapter() == SenderStore.Adapter.LOGGING
            || (row.channel() == SenderStore.Channel.SMS
                && row.adapter() == SenderStore.Adapter.HTTP_SMS)
            || (row.channel() == SenderStore.Channel.EMAIL
                && row.adapter() == SenderStore.Adapter.SMTP);
    if (!suits || senders.problem(row).isPresent()) {
      throw new SolaceException(SenderError.INVALID);
    }
  }

  private SenderStore.By superAdmin(Jwt token) {
    var staff = access.require(token, AREA, Level.CHANGE);
    return new SenderStore.By(staff.id(), staff.name());
  }

  private View view(SenderStore.Sender s) {
    return new View(
        s.code(),
        s.channel(),
        s.adapter(),
        s.displayName(),
        s.status(),
        s.priority(),
        s.secretRef(),
        s.settings(),
        s.version(),
        s.updatedByName(),
        s.updatedAt(),
        senders.keysFound(s),
        senders.problem(s).orElse(null),
        senders.circuit(s.code()).orElse(null));
  }
}
