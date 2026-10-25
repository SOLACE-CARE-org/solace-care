package care.solace.core.privacy;

import care.solace.audit.AuditLog;
import care.solace.core.identity.Account;
import care.solace.core.identity.Accounts;
import care.solace.core.identity.PersonalData;
import care.solace.core.notifications.Delivery;
import care.solace.core.system.ObjectStorage;
import care.solace.errors.CommonError;
import care.solace.errors.SolaceException;
import care.solace.events.Events;
import care.solace.jobs.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data subject rights (System Design, Security; D40): a copy of one's data, prepared in the
 * background and kept [7] days; erasure for seekers at once, and as a request to Operations for
 * specialists. Each module erases its own data through {@link PersonalData}.
 */
@Service
class DataRights {

  private static final Logger LOG = LoggerFactory.getLogger(DataRights.class);
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.INDENT_OUTPUT);

  private final JdbcClient db;
  private final List<PersonalData> modules;
  private final Accounts accounts;
  private final ObjectStorage storage;
  private final Delivery delivery;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final AuditLog audit;
  private final PrivacySettings settings;
  private final care.solace.core.admin.PlatformSettings platform;
  private final Clock clock;

  DataRights(
      JdbcClient db,
      List<PersonalData> modules,
      Accounts accounts,
      ObjectStorage storage,
      Delivery delivery,
      ApplicationEventPublisher events,
      TransactionTemplate transactions,
      AuditLog audit,
      PrivacySettings settings,
      care.solace.core.admin.PlatformSettings platform,
      Clock clock) {
    this.db = db;
    this.modules = modules;
    this.accounts = accounts;
    this.storage = storage;
    this.delivery = delivery;
    this.events = events;
    this.transactions = transactions;
    this.audit = audit;
    this.settings = settings;
    this.platform = platform;
    this.clock = clock;
  }

  record Request(
      UUID id,
      UUID accountId,
      String kind,
      String status,
      Instant requestedAt,
      Instant dueAt,
      Instant readyAt,
      Instant expiresAt,
      String fileKey) {}

  // --- A copy of the data -----------------------------------------------------------------------

  /** One copy is prepared at a time; asking again returns it. */
  Request requestExport(UUID accountId) {
    return latestExport(accountId)
        .filter(r -> r.status().equals("PREPARING"))
        .orElseGet(
            () -> {
              Instant now = clock.instant();
              UUID id = UUID.randomUUID();
              insert(id, accountId, "EXPORT", "PREPARING", now);
              return find(id).orElseThrow();
            });
  }

  Optional<Request> latestExport(UUID accountId) {
    return db.sql(
            """
            SELECT * FROM data_request WHERE account_id = :a AND kind = 'EXPORT'
             ORDER BY requested_at DESC LIMIT 1
            """)
        .param("a", accountId)
        .query(DataRights::request)
        .optional()
        .map(this::expiredIfPast);
  }

  @Job(
      name = "core.privacy.prepare-exports",
      description = "Builds requested data exports",
      every = "PT30S",
      initialDelay = "PT30S")
  void prepareExports() {
    try {
      prepareNow();
    } catch (RuntimeException e) {
      LOG.warn("Preparing data exports failed", e);
    }
  }

  void prepareNow() {
    var waiting =
        db.sql("SELECT * FROM data_request WHERE kind = 'EXPORT' AND status = 'PREPARING' LIMIT 20")
            .query(DataRights::request)
            .list();
    for (Request r : waiting) {
      Map<String, Object> copy = new LinkedHashMap<>();
      copy.put("preparedAt", clock.instant());
      for (PersonalData module : modules) {
        Object section = module.export(r.accountId());
        if (section != null) {
          copy.put(module.section(), section);
        }
      }
      String key = "exports/" + r.id() + ".json";
      try {
        storage.put(settings.exportBucket(), key, JSON.writeValueAsBytes(copy), "application/json");
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      Instant now = clock.instant();
      transactions.executeWithoutResult(
          s -> {
            db.sql(
                    """
                    UPDATE data_request SET status = 'READY', ready_at = :now, expires_at = :exp,
                           file_key = :k, completed_at = :now
                     WHERE id = :id AND status = 'PREPARING'
                    """)
                .param("id", r.id())
                .param("now", Timestamp.from(now))
                .param(
                    "exp",
                    Timestamp.from(
                        now.plus(
                            platform.duration(
                                "solace.privacy.export-lifetime", settings.exportLifetime()))))
                .param("k", key)
                .update();
            events.publishEvent(
                new DataCopyReady(
                    r.accountId(),
                    platform
                        .duration("solace.privacy.export-lifetime", settings.exportLifetime())
                        .toDays()));
          });
    }
  }

  /** The file, for its owner, while it lasts; audited like a read of their record. */
  byte[] download(UUID accountId, UUID exportId) {
    var r =
        find(exportId)
            .filter(x -> x.accountId().equals(accountId) && x.kind().equals("EXPORT"))
            .map(this::expiredIfPast)
            .orElseThrow(() -> new SolaceException(CommonError.NOT_FOUND));
    if (!r.status().equals("READY")) {
      throw new SolaceException(PrivacyError.EXPORT_NOT_READY);
    }
    audit.read(
        "privacy.export.download", "data-request", exportId.toString(), accountId.toString());
    try (InputStream in = storage.get(settings.exportBucket(), r.fileKey())) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Erasure ----------------------------------------------------------------------------------

  enum Outcome {
    ERASED,
    REQUESTED
  }

  Outcome erase(Account account) {
    UUID id = account.id();
    Instant now = clock.instant();
    if (account.type() == Account.Type.SPECIALIST) {
      boolean open =
          db.sql(
                  """
                  SELECT EXISTS (SELECT 1 FROM data_request WHERE account_id = :a
                                  AND kind = 'ERASURE' AND status = 'REQUESTED')
                  """)
              .param("a", id)
              .query(Boolean.class)
              .single();
      if (!open) {
        insert(UUID.randomUUID(), id, "ERASURE", "REQUESTED", now);
      }
      return Outcome.REQUESTED;
    }
    for (PersonalData module : modules) {
      var blocked = module.blocksErasure(id);
      if (blocked.isPresent()) {
        throw new SolaceException(
            blocked.get().equals("privacy.open-refund")
                ? PrivacyError.OPEN_REFUND
                : PrivacyError.UPCOMING_SESSIONS);
      }
    }
    // The goodbye goes before the address is erased (notification-copy.md, Account deleted).
    accounts
        .email(id)
        .filter(e -> !e.isBlank())
        .ifPresent(
            email -> {
              Locale locale = account.appLanguage();
              try {
                delivery.email(
                    email,
                    delivery.text("email.account-deleted.subject", locale),
                    delivery.text("email.account-deleted.body", locale));
              } catch (RuntimeException e) {
                LOG.warn("The account-deleted email could not be sent");
              }
            });
    transactions.executeWithoutResult(
        s -> {
          for (PersonalData module : modules) {
            module.erase(id);
          }
          UUID request = UUID.randomUUID();
          insert(request, id, "ERASURE", "DONE", now);
          db.sql("UPDATE data_request SET completed_at = :now WHERE id = :id")
              .param("id", request)
              .param("now", Timestamp.from(now))
              .update();
          events.publishEvent(
              Events.envelope(
                  "core.user.erased",
                  1,
                  id.toString(),
                  account.appLanguage().getLanguage(),
                  new UserErased(account.type().name())));
        });
    audit.adminAction("privacy.erasure", "account", id.toString(), Map.of());
    return Outcome.ERASED;
  }

  /** core.user.erased v1. */
  record UserErased(String accountType) {}

  // --- Retention --------------------------------------------------------------------------------

  /** Copies past their [7] days are deleted (D40). */
  @Job(
      name = "core.privacy.expire-exports",
      description = "Deletes export files past their lifetime",
      cron = "0 15 2 * * *",
      zone = "Africa/Douala")
  void expireExports() {
    try {
      Instant now = clock.instant();
      var expired =
          db.sql(
                  """
                  SELECT * FROM data_request WHERE kind = 'EXPORT' AND status = 'READY'
                     AND expires_at < :now
                  """)
              .param("now", Timestamp.from(now))
              .query(DataRights::request)
              .list();
      for (Request r : expired) {
        storage.delete(settings.exportBucket(), r.fileKey());
        db.sql("UPDATE data_request SET status = 'EXPIRED', file_key = NULL WHERE id = :id")
            .param("id", r.id())
            .update();
      }
    } catch (RuntimeException e) {
      LOG.warn("Expiring data exports failed", e);
    }
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private void insert(UUID id, UUID accountId, String kind, String status, Instant now) {
    db.sql(
            """
            INSERT INTO data_request (id, account_id, kind, status, requested_at, due_at)
            VALUES (:id, :a, :k, :s, :now, :due)
            """)
        .param("id", id)
        .param("a", accountId)
        .param("k", kind)
        .param("s", status)
        .param("now", Timestamp.from(now))
        .param(
            "due",
            Timestamp.from(
                now.plus(
                    platform.duration(
                        "solace.privacy.request-deadline", settings.requestDeadline()))))
        .update();
  }

  private Optional<Request> find(UUID id) {
    return db.sql("SELECT * FROM data_request WHERE id = :id")
        .param("id", id)
        .query(DataRights::request)
        .optional();
  }

  private Request expiredIfPast(Request r) {
    return r.status().equals("READY") && r.expiresAt().isBefore(clock.instant())
        ? new Request(
            r.id(),
            r.accountId(),
            r.kind(),
            "EXPIRED",
            r.requestedAt(),
            r.dueAt(),
            r.readyAt(),
            r.expiresAt(),
            r.fileKey())
        : r;
  }

  private static Request request(ResultSet rs, int n) throws SQLException {
    return new Request(
        rs.getObject("id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getString("kind"),
        rs.getString("status"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getTimestamp("due_at").toInstant(),
        instant(rs, "ready_at"),
        instant(rs, "expires_at"),
        rs.getString("file_key"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
