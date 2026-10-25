package care.solace.core.notifications;

import care.solace.audit.AuditLog;
import care.solace.core.admin.StaffAccess;
import care.solace.core.admin.StaffAccess.Level;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification copy for staff (tag admin-content; D61): every push, SMS and email in EN and FR, the
 * approved wording beside any edit. Super admins and Content editors change it, both languages at
 * once, with a reason; the copy rules are checked first. A change is in force on every instance
 * within a minute. Restoring the approved wording is a change like any other.
 */
@RestController
class NotificationCopyController {

  private static final String AREA = "content";

  private final StaffAccess access;
  private final Copy copy;
  private final CopyStore store;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  NotificationCopyController(
      StaffAccess access,
      Copy copy,
      CopyStore store,
      AuditLog audit,
      TransactionTemplate transactions,
      Clock clock) {
    this.access = access;
    this.copy = copy;
    this.store = store;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * @param channel sms, push or email, or word and category for the words other messages use
   * @param forStaff staff messages name a crisis category on purpose and skip the wording check
   * @param version 0 while the approved wording is in force
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Message(
      String key,
      String channel,
      boolean forStaff,
      String en,
      String fr,
      String approvedEn,
      String approvedFr,
      int version,
      String updatedBy,
      Instant updatedAt) {}

  record Change(
      @Min(0) int version,
      @NotBlank @Size(max = 1000) String en,
      @NotBlank @Size(max = 1000) String fr,
      @NotBlank @Size(max = 500) String reason) {}

  @GetMapping("/admin/v1/notification-copy")
  List<Message> list(@AuthenticationPrincipal Jwt token) {
    access.require(token, AREA, Level.VIEW);
    var edited = copy.editedCopy();
    return copy.approvedCopy().stream().map(a -> view(a, edited.get(a.key()))).toList();
  }

  @PutMapping("/admin/v1/notification-copy/{key}")
  Message change(
      @AuthenticationPrincipal Jwt token,
      @PathVariable String key,
      @Valid @RequestBody Change body) {
    var staff = access.require(token, AREA, Level.CHANGE);
    var approved = approved(key);
    String en = body.en().strip();
    String fr = body.fr().strip();
    CopyRules.check(key, en, approved.en(), Locale.ENGLISH)
        .or(() -> CopyRules.check(key, fr, approved.fr(), Locale.FRENCH))
        .ifPresent(
            problem -> {
              throw new SolaceException(CopyError.of(problem));
            });
    boolean saved =
        Boolean.TRUE.equals(
            transactions.execute(
                s ->
                    store.save(
                        key,
                        body.version(),
                        en,
                        fr,
                        body.reason().strip(),
                        staff.id(),
                        staff.name(),
                        clock.instant())));
    if (!saved) {
      throw new SolaceException(CopyError.CHANGED);
    }
    copy.reload();
    audit.adminAction(
        "notifications.copy.change",
        "notification_copy",
        key,
        body.reason().strip(),
        Map.of("version", String.valueOf(body.version() + 1)));
    return view(approved, copy.editedCopy().get(key));
  }

  @GetMapping("/admin/v1/notification-copy/{key}/history")
  List<CopyStore.Version> history(@AuthenticationPrincipal Jwt token, @PathVariable String key) {
    access.require(token, AREA, Level.VIEW);
    approved(key);
    return store.versions(key);
  }

  private Copy.Approved approved(String key) {
    return copy.approvedCopy().stream()
        .filter(a -> a.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
  }

  private static Message view(Copy.Approved a, CopyStore.Edited e) {
    return new Message(
        a.key(),
        a.key().substring(0, a.key().indexOf('.')),
        CopyRules.forStaff(a.key()),
        e == null ? a.en() : e.en(),
        e == null ? a.fr() : e.fr(),
        a.en(),
        a.fr(),
        e == null ? 0 : e.version(),
        e == null ? null : e.updatedByName(),
        e == null ? null : e.updatedAt());
  }
}
