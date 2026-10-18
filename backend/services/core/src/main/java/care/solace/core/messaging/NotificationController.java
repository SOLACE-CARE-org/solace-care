package care.solace.core.messaging;

import care.solace.core.identity.Accounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Push devices, notification settings and the inbox (tag notifications). */
@RestController
class NotificationController {

  private final JdbcClient db;
  private final Accounts accounts;
  private final Clock clock;

  NotificationController(JdbcClient db, Accounts accounts, Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.clock = clock;
  }

  record Device(
      @NotBlank @Size(max = 300) String token,
      @NotNull @Pattern(regexp = "IOS|ANDROID") String platform,
      @NotBlank @Size(max = 64) String timeZone) {}

  record Settings(@NotNull Boolean sessionReminders, @NotNull Boolean discreet) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record InboxItem(UUID id, String kind, String text, UUID bookingId, Instant at, boolean read) {}

  @PutMapping("/v1/me/devices")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void register(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Device body) {
    var account = accounts.require(token);
    String zone;
    try {
      zone = ZoneId.of(body.timeZone()).getId();
    } catch (RuntimeException e) {
      throw new care.solace.errors.SolaceException(care.solace.errors.CommonError.REQUEST_INVALID);
    }
    // A token belongs to one account: signing in as someone else on the device moves it.
    db.sql(
            """
            INSERT INTO push_device (token, account_id, platform, time_zone, updated_at)
            VALUES (:t, :a, :p, :z, :now)
            ON CONFLICT (token) DO UPDATE SET account_id = EXCLUDED.account_id,
                   platform = EXCLUDED.platform, time_zone = EXCLUDED.time_zone,
                   updated_at = EXCLUDED.updated_at
            """)
        .param("t", body.token())
        .param("a", account.id())
        .param("p", body.platform())
        .param("z", zone)
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  @DeleteMapping("/v1/me/devices/{token}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void forget(@AuthenticationPrincipal Jwt jwt, @PathVariable("token") String device) {
    var account = accounts.require(jwt);
    db.sql("DELETE FROM push_device WHERE token = :t AND account_id = :a")
        .param("t", device)
        .param("a", account.id())
        .update();
  }

  @GetMapping("/v1/me/notification-settings")
  Settings settings(@AuthenticationPrincipal Jwt token) {
    var account = accounts.require(token);
    return db.sql(
            "SELECT session_reminders, discreet FROM notification_setting WHERE account_id = :a")
        .param("a", account.id())
        .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getBoolean(2)))
        .optional()
        .orElse(new Settings(true, false));
  }

  @PutMapping("/v1/me/notification-settings")
  Settings save(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Settings body) {
    var account = accounts.require(token);
    db.sql(
            """
            INSERT INTO notification_setting (account_id, session_reminders, discreet)
            VALUES (:a, :r, :d)
            ON CONFLICT (account_id) DO UPDATE SET session_reminders = EXCLUDED.session_reminders,
                   discreet = EXCLUDED.discreet
            """)
        .param("a", account.id())
        .param("r", body.sessionReminders())
        .param("d", body.discreet())
        .update();
    return body;
  }

  @GetMapping("/v1/me/notifications")
  List<InboxItem> inbox(@AuthenticationPrincipal Jwt token) {
    var account = accounts.require(token);
    return db.sql(
            """
            SELECT id, kind, text, booking_id, created_at, read_at FROM notification
             WHERE account_id = :a AND channel = 'PUSH' AND status = 'SENT'
             ORDER BY created_at DESC LIMIT 50
            """)
        .param("a", account.id())
        .query(
            (rs, n) ->
                new InboxItem(
                    rs.getObject("id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("text"),
                    rs.getObject("booking_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("read_at") != null))
        .list();
  }

  @PostMapping("/v1/me/notifications/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void readAll(@AuthenticationPrincipal Jwt token) {
    var account = accounts.require(token);
    db.sql("UPDATE notification SET read_at = :now WHERE account_id = :a AND read_at IS NULL")
        .param("a", account.id())
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }
}
