package care.solace.core.privacy;

import care.solace.core.identity.Account;
import care.solace.core.identity.ConsentProgress;
import care.solace.events.Events;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent records: every choice is appended with the wording version the person saw, and every
 * change is published as {@code core.consent.changed} so Tracking and AI can act on it.
 */
@Service
class Consents implements ConsentProgress {

  private final JdbcClient db;
  private final PrivacySettings settings;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  Consents(JdbcClient db, PrivacySettings settings, ApplicationEventPublisher events, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.events = events;
    this.clock = clock;
  }

  /** The current choice for one purpose. */
  record Current(Purpose purpose, boolean granted, String version, Instant recordedAt) {}

  @Override
  public boolean recordedAny(UUID accountId) {
    return db.sql("SELECT EXISTS (SELECT 1 FROM consent_record WHERE account_id = :a)")
        .param("a", accountId)
        .query(Boolean.class)
        .single();
  }

  /** The offered purposes, each with its latest choice, or "not granted" when never asked. */
  List<Current> current(UUID accountId) {
    Map<Purpose, Current> latest = latest(accountId);
    List<Current> result = new ArrayList<>();
    for (Purpose purpose : settings.offeredPurposes()) {
      result.add(
          Optional.ofNullable(latest.get(purpose))
              .orElse(new Current(purpose, false, settings.versionOf(purpose), null)));
    }
    return result;
  }

  /**
   * Records the choices that differ from the current ones (or were never made). Purposes not
   * offered in this release are refused by the caller.
   */
  @Transactional
  void record(Account account, Map<Purpose, Boolean> choices) {
    Map<Purpose, Current> latest = latest(account.id());
    Instant now = clock.instant();
    for (var choice : choices.entrySet()) {
      Purpose purpose = choice.getKey();
      boolean granted = choice.getValue();
      String version = settings.versionOf(purpose);
      Current before = latest.get(purpose);
      if (before != null && before.granted() == granted && before.version().equals(version)) {
        continue;
      }
      db.sql(
              """
              INSERT INTO consent_record (account_id, purpose, granted, version, recorded_at)
              VALUES (:a, :p, :g, :v, :t)
              """)
          .param("a", account.id())
          .param("p", purpose.name())
          .param("g", granted)
          .param("v", version)
          .param("t", Timestamp.from(now))
          .update();
      events.publishEvent(
          Events.envelope(
              ConsentChanged.TYPE,
              ConsentChanged.VERSION,
              account.id().toString(),
              account.appLanguage().getLanguage(),
              new ConsentChanged(purpose.name(), granted, version)));
    }
  }

  private Map<Purpose, Current> latest(UUID accountId) {
    Map<Purpose, Current> latest = new EnumMap<>(Purpose.class);
    db.sql(
            """
            SELECT DISTINCT ON (purpose) purpose, granted, version, recorded_at
              FROM consent_record WHERE account_id = :a
             ORDER BY purpose, recorded_at DESC, id DESC
            """)
        .param("a", accountId)
        .query(
            (rs, n) ->
                new Current(
                    Purpose.valueOf(rs.getString("purpose")),
                    rs.getBoolean("granted"),
                    rs.getString("version"),
                    rs.getTimestamp("recorded_at").toInstant()))
        .list()
        .forEach(c -> latest.put(c.purpose(), c));
    return latest;
  }
}
