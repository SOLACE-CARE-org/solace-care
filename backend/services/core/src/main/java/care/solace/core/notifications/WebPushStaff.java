package care.solace.core.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link StaffPush} through each browser's push service, encrypted (RFC 8291) and signed with
 * Solace's VAPID key (RFC 8292). Without a VAPID key (local runs, tests) messages are only logged,
 * as with the other senders (D38). Subscriptions the push service no longer knows are forgotten.
 */
@Service
class WebPushStaff implements StaffPush {

  private static final Logger LOG = LoggerFactory.getLogger(WebPushStaff.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  record Subscription(
      String endpoint, String staffId, String p256dh, String auth, String language) {}

  private final JdbcClient db;
  private final Delivery delivery;
  private final Optional<WebPush.Vapid> vapid;
  private final String subject;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  WebPushStaff(
      JdbcClient db,
      Delivery delivery,
      @Value("${solace.notifications.vapid.public-key:}") String publicKey,
      @Value("${solace.notifications.vapid.private-key:}") String privateKey,
      @Value("${solace.notifications.vapid.subject:mailto:ops@solace.care}") String subject,
      Clock clock) {
    this.db = db;
    this.delivery = delivery;
    this.vapid =
        publicKey.isBlank() || privateKey.isBlank()
            ? Optional.empty()
            : Optional.of(WebPush.Vapid.of(publicKey, privateKey));
    this.subject = subject;
    this.clock = clock;
  }

  /** The key browsers subscribe with; empty while web push is not set up. */
  Optional<String> publicKey() {
    return vapid.map(WebPush.Vapid::publicKeyB64);
  }

  void subscribe(Subscription s) {
    db.sql(
            """
            INSERT INTO staff_push_subscription (endpoint, staff_id, p256dh, auth, language, created_at)
            VALUES (:e, :s, :p, :a, :l, :now)
            ON CONFLICT (endpoint) DO UPDATE
               SET staff_id = EXCLUDED.staff_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth,
                   language = EXCLUDED.language
            """)
        .param("e", s.endpoint())
        .param("s", s.staffId())
        .param("p", s.p256dh())
        .param("a", s.auth())
        .param("l", s.language())
        .param("now", Timestamp.from(clock.instant()))
        .update();
  }

  void unsubscribe(String staffId, String endpoint) {
    db.sql("DELETE FROM staff_push_subscription WHERE staff_id = :s AND endpoint = :e")
        .param("s", staffId)
        .param("e", endpoint)
        .update();
  }

  int subscriptions(String staffId) {
    return db.sql("SELECT count(*) FROM staff_push_subscription WHERE staff_id = :s")
        .param("s", staffId)
        .query(Integer.class)
        .single();
  }

  @Override
  public void send(Collection<String> staffIds, String key, boolean urgent, Object... arguments) {
    if (staffIds.isEmpty()) {
      return;
    }
    List<Subscription> targets =
        db.sql("SELECT * FROM staff_push_subscription WHERE staff_id IN (:ids)")
            .param("ids", staffIds)
            .query(
                (rs, n) ->
                    new Subscription(
                        rs.getString("endpoint"),
                        rs.getString("staff_id"),
                        rs.getString("p256dh"),
                        rs.getString("auth"),
                        rs.getString("language")))
            .list();
    for (Subscription s : targets) {
      Locale locale = "fr".equals(s.language()) ? Locale.FRENCH : Locale.ENGLISH;
      String text = delivery.text(key, locale, arguments);
      if (vapid.isEmpty()) {
        LOG.info("Staff push ({}) to subscription of staff {}", key, s.staffId());
        continue;
      }
      try {
        deliver(s, text, urgent);
      } catch (Exception e) {
        LOG.warn("Staff push ({}) failed", key, e);
      }
    }
  }

  private void deliver(Subscription s, String text, boolean urgent) throws Exception {
    byte[] payload =
        JSON.writeValueAsString(Map.of("title", "Solace", "body", text))
            .getBytes(StandardCharsets.UTF_8);
    URI endpoint = URI.create(s.endpoint());
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("TTL", urgent ? "3600" : "86400")
            .header("Urgency", urgent ? "high" : "normal")
            .header(
                "Authorization",
                WebPush.authorization(vapid.orElseThrow(), endpoint, subject, clock.instant()))
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    WebPush.encrypt(payload, s.p256dh(), s.auth())))
            .build();
    int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (status == 404 || status == 410) {
      db.sql("DELETE FROM staff_push_subscription WHERE endpoint = :e")
          .param("e", s.endpoint())
          .update();
    } else if (status >= 300) {
      LOG.warn("Push service answered {}", status);
    }
  }
}
