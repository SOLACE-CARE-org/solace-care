package care.solace.core.guests;

import care.solace.core.identity.ContactVerified;
import care.solace.core.notifications.SignInCodes;
import care.solace.errors.SolaceException;
import care.solace.jobs.Job;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link GuestAccess}. Codes live in Redis (System Design: Redis for OTP), hashed, with their
 * remaining tries; guest contacts and token hashes live in PostgreSQL.
 */
@Service
@EnableConfigurationProperties(GuestSettings.class)
class Guests implements GuestAccess {

  private static final String CODE = "solace:guest-code:";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Duration retention;
  private final JdbcClient db;
  private final StringRedisTemplate redis;
  private final SignInCodes codes;
  private final GuestSettings settings;
  private final care.solace.core.admin.PlatformSettings platform;
  private final care.solace.core.admin.TestCodes testCodes;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  Guests(
      JdbcClient db,
      StringRedisTemplate redis,
      SignInCodes codes,
      GuestSettings settings,
      care.solace.core.admin.PlatformSettings platform,
      care.solace.core.admin.TestCodes testCodes,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      @org.springframework.beans.factory.annotation.Value("${solace.privacy.guest-retention:P365D}")
          Duration retention,
      Clock clock) {
    this.retention = retention;
    this.db = db;
    this.redis = redis;
    this.codes = codes;
    this.settings = settings;
    this.platform = platform;
    this.testCodes = testCodes;
    this.events = events;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public void sendCode(String phone, Locale locale) {
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    redis
        .opsForValue()
        .set(
            CODE + phone,
            hash(code)
                + "|"
                + platform.number("solace.guests.code-attempts", settings.codeAttempts()),
            platform.duration("solace.guests.code-lifetime", settings.codeLifetime()));
    codes.send(
        SignInCodes.Channel.SMS,
        phone,
        code,
        (int) platform.duration("solace.guests.code-lifetime", settings.codeLifetime()).toMinutes(),
        locale);
  }

  @Override
  public UUID verify(String phone, String code, Locale locale) {
    String key = CODE + phone;
    if (!testCodes.acceptsSignIn(code)) {
      check(key, code);
    }
    redis.delete(key);
    Instant now = clock.instant();
    return transactions.execute(
        s ->
            db.sql(
                    """
                    INSERT INTO guest_contact (id, phone, preferred_locale, verified_at)
                    VALUES (:id, :p, :l, :now)
                    ON CONFLICT (phone) DO UPDATE
                       SET verified_at = EXCLUDED.verified_at,
                           preferred_locale = EXCLUDED.preferred_locale
                    RETURNING id
                    """)
                .param("id", UUID.randomUUID())
                .param("p", phone)
                .param("l", "fr".equals(locale.getLanguage()) ? "fr" : "en")
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .single());
  }

  /** Refuses a wrong or missing code, counting the try against the code. */
  private void check(String key, String code) {
    String stored = redis.opsForValue().get(key);
    if (stored == null) {
      throw new SolaceException(GuestError.CODE_REJECTED);
    }
    String[] parts = stored.split("\\|");
    if (!MessageDigest.isEqual(
        parts[0].getBytes(StandardCharsets.US_ASCII),
        hash(code).getBytes(StandardCharsets.US_ASCII))) {
      int left = Integer.parseInt(parts[1]) - 1;
      if (left <= 0) {
        redis.delete(key);
      } else {
        // Keep the code's remaining lifetime; only the tries go down.
        Long ttl = redis.getExpire(key);
        Duration keep =
            ttl != null && ttl > 0
                ? Duration.ofSeconds(ttl)
                : platform.duration("solace.guests.code-lifetime", settings.codeLifetime());
        redis.opsForValue().set(key, parts[0] + "|" + left, keep);
      }
      throw new SolaceException(GuestError.CODE_REJECTED);
    }
  }

  @Override
  public String issueToken(UUID guestContactId, UUID bookingId, Instant expiresAt) {
    return issue(guestContactId, bookingId, expiresAt, 32);
  }

  @Override
  public String issueLinkToken(UUID guestContactId, UUID bookingId, Instant expiresAt) {
    return issue(guestContactId, bookingId, expiresAt, 10);
  }

  private String issue(UUID guestContactId, UUID bookingId, Instant expiresAt, int bytes) {
    byte[] raw = new byte[bytes];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    db.sql(
            """
            INSERT INTO guest_token (token_hash, guest_contact_id, booking_id, expires_at)
            VALUES (:h, :g, :b, :e)
            """)
        .param("h", hash(token))
        .param("g", guestContactId)
        .param("b", bookingId)
        .param("e", Timestamp.from(expiresAt))
        .update();
    return token;
  }

  @Override
  public Optional<GuestPass> check(String token, Instant now) {
    if (token == null || token.isBlank() || token.length() > 100) {
      return Optional.empty();
    }
    return db.sql(
            """
            SELECT guest_contact_id, booking_id FROM guest_token
             WHERE token_hash = :h AND expires_at > :now
            """)
        .param("h", hash(token))
        .param("now", Timestamp.from(now))
        .query(
            (rs, n) ->
                new GuestPass(
                    rs.getObject("guest_contact_id", UUID.class),
                    rs.getObject("booking_id", UUID.class)))
        .optional();
  }

  /**
   * Guest contacts lose their phone and links once unused for their retention (System Design, 12
   * months after the last booking; D40). Each guest booking proves the phone again, so the last
   * proof is when the contact was last used.
   */
  @Job(
      name = "core.guests.purge-contacts",
      description = "Removes guest phone numbers long after their last booking",
      cron = "0 45 2 * * *",
      zone = "Africa/Douala")
  void purgeOldContacts() {
    Instant now = clock.instant();
    Instant before = now.minus(platform.duration("solace.privacy.guest-retention", retention));
    db.sql(
            """
            DELETE FROM guest_token WHERE guest_contact_id IN
              (SELECT id FROM guest_contact WHERE phone IS NOT NULL AND verified_at < :before)
            """)
        .param("before", Timestamp.from(before))
        .update();
    db.sql(
            """
            UPDATE guest_contact SET phone = NULL, purged_at = :now
             WHERE phone IS NOT NULL AND verified_at < :before
            """)
        .param("before", Timestamp.from(before))
        .param("now", Timestamp.from(now))
        .update();
  }

  @Override
  public GuestPass require(String token, Instant now) {
    return check(token, now).orElseThrow(() -> new SolaceException(GuestError.TOKEN_INVALID));
  }

  @Override
  public Optional<Locale> locale(UUID guestContactId) {
    return db.sql("SELECT preferred_locale FROM guest_contact WHERE id = :id")
        .param("id", guestContactId)
        .query(String.class)
        .optional()
        .map(Locale::of);
  }

  @Override
  public Optional<String> phone(UUID guestContactId) {
    return db.sql("SELECT phone FROM guest_contact WHERE id = :id")
        .param("id", guestContactId)
        .query(String.class)
        .optional();
  }

  @Override
  public Optional<String> maskedPhone(UUID guestContactId) {
    return phone(guestContactId).map(Guests::mask);
  }

  /**
   * A member's first sign-in proved a phone: any guest contact with that phone becomes theirs, and
   * Bookings moves the bookings (D34). Runs after the sign-in commits, retried until it succeeds.
   */
  @ApplicationModuleListener
  void claim(ContactVerified verified) {
    if (verified.phone() == null) {
      return;
    }
    Instant now = clock.instant();
    List<UUID> claimed =
        db.sql(
                """
                UPDATE guest_contact SET claimed_by_account_id = :a, claimed_at = :now
                 WHERE phone = :p AND claimed_by_account_id IS NULL
                RETURNING id
                """)
            .param("a", verified.accountId())
            .param("p", verified.phone())
            .param("now", Timestamp.from(now))
            .query(UUID.class)
            .list();
    for (UUID id : claimed) {
      events.publishEvent(new GuestContactClaimed(id, verified.accountId()));
    }
  }

  /** "+237677000000" as "+237 6 •• •• •• 00". */
  static String mask(String phone) {
    if (phone.startsWith("+237") && phone.length() == 13) {
      return "+237 " + phone.charAt(4) + " •• •• •• " + phone.substring(11);
    }
    return phone.substring(0, Math.min(4, phone.length()))
        + " •• "
        + phone.substring(phone.length() - 2);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
