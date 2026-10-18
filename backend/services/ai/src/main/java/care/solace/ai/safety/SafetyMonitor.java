package care.solace.ai.safety;

import care.solace.events.Events;
import care.solace.events.Topics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * The safety monitor (System Design, AI; D39): every text it checks gets a decision, recorded with
 * the classifier and policy that made it; a flag is published as {@code ai.risk.flagged}, ids and
 * versions only, in the same transaction.
 */
@Service
@EnableConfigurationProperties(SafetySettings.class)
class SafetyMonitor {

  static final String TOPIC = "ai.risk";

  private final List<SafetyClassifier> classifiers;
  private final JdbcClient db;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final SafetySettings settings;
  private final Clock clock;

  SafetyMonitor(
      SafetySettings settings,
      RestClient.Builder restClients,
      JdbcClient db,
      ApplicationEventPublisher events,
      TransactionTemplate transactions) {
    this.settings = settings;
    this.classifiers =
        settings.model().enabled()
            ? List.of(
                new PhraseClassifier(),
                new ModelClassifier(restClients, settings.model().url(), settings.model().name()))
            : List.of(new PhraseClassifier());
    this.db = db;
    this.events = events;
    this.transactions = transactions;
    this.clock = Clock.systemUTC();
  }

  record Decision(UUID id, boolean flagged, SafetyClassifier.Category category) {}

  record Stored(
      UUID id,
      String source,
      UUID sourceId,
      UUID subjectId,
      String category,
      String classifierVersion,
      String policyVersion) {}

  /** Checks one text; flagged if any classifier flags it. */
  Decision check(String source, UUID sourceId, UUID subjectId, String text, Locale locale) {
    SafetyClassifier.Category category = null;
    String version = classifiers.getFirst().version();
    for (SafetyClassifier c : classifiers) {
      var found = c.classify(text, locale);
      if (found.isPresent()) {
        category = found.get();
        version = c.version();
        break;
      }
    }
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    SafetyClassifier.Category flagged = category;
    String classifierVersion = version;
    transactions.executeWithoutResult(
        s -> {
          db.sql(
                  """
                  INSERT INTO safety_decision (id, source, source_id, subject_id, flagged, category,
                                               classifier_version, policy_version, created_at)
                  VALUES (:id, :src, :sid, :sub, :f, :c, :cv, :pv, :now)
                  """)
              .param("id", id)
              .param("src", source)
              .param("sid", sourceId)
              .param("sub", subjectId)
              .param("f", flagged != null)
              .param("c", flagged == null ? null : flagged.name())
              .param("cv", classifierVersion)
              .param("pv", settings.policyVersion())
              .param("now", Timestamp.from(now))
              .update();
          if (flagged != null) {
            events.publishEvent(
                Events.envelope(
                    "ai.risk.flagged",
                    1,
                    subjectId.toString(),
                    locale.getLanguage(),
                    new Flagged(
                        id, source, sourceId, classifierVersion, settings.policyVersion())));
          }
        });
    return new Decision(id, flagged != null, flagged);
  }

  Optional<Stored> decision(UUID id) {
    return db.sql(
            """
            SELECT id, source, source_id, subject_id, category, classifier_version, policy_version
              FROM safety_decision WHERE id = :id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new Stored(
                    rs.getObject("id", UUID.class),
                    rs.getString("source"),
                    rs.getObject("source_id", UUID.class),
                    rs.getObject("subject_id", UUID.class),
                    rs.getString("category"),
                    rs.getString("classifier_version"),
                    rs.getString("policy_version")))
        .optional();
  }

  /** ai.risk.flagged v1 (contracts/events/ai). */
  record Flagged(
      UUID decisionId,
      String source,
      UUID sourceId,
      String classifierVersion,
      String policyVersion) {}

  @Bean
  static KafkaAdmin.NewTopics riskTopics(
      @Value("${solace.events.partitions:3}") int partitions,
      @Value("${solace.events.replicas:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        Topics.withDeadLetter(TOPIC, partitions, replicas).toArray(NewTopic[]::new));
  }
}
