package care.solace.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the outbox and Kafka for a service:
 *
 * <ul>
 *   <li>every {@link EventEnvelope} published in a transaction is externalised to its topic, keyed
 *       by subject;
 *   <li>{@link IdempotentConsumer} for listeners;
 *   <li>failed records are retried twice, then sent to {@code <topic>.dlt};
 *   <li>listener containers run on platform threads, even where virtual threads are on;
 *   <li>the outbox and processed-event migrations join the service's Flyway locations.
 * </ul>
 */
@AutoConfiguration(
    after = {
      KafkaAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    })
public class EventsAutoConfiguration {

  public static final String MIGRATIONS = "classpath:db/events";

  @Bean
  @ConditionalOnMissingBean
  EventExternalizationConfiguration solaceEventExternalization() {
    return EventExternalizationConfiguration.externalizing()
        .select(event -> event instanceof EventEnvelope)
        .route(
            EventEnvelope.class,
            event -> RoutingTarget.forTarget(event.topic()).andKey(event.partitionKey()))
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
  IdempotentConsumer idempotentConsumer(
      JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    return new IdempotentConsumer(jdbc, transactions);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(KafkaTemplate.class)
  static class DeadLetters {

    /**
     * Retries a failing record twice, one second apart, then publishes it to {@code <topic>.dlt}.
     * Records that could not even be read go there straight away, as raw bytes.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler solaceKafkaErrorHandler(ProducerFactory<?, ?> serviceProducers) {
      // Same broker and settings as the service's own producer. EventJsonSerializer forwards
      // unreadable records as the raw bytes they arrived as.
      DefaultKafkaProducerFactory<Object, Object> producers =
          new DefaultKafkaProducerFactory<>(
              new HashMap<>(serviceProducers.getConfigurationProperties()),
              keySerializer(),
              new EventJsonSerializer());
      KafkaTemplate<Object, Object> deadLetterTemplate = new KafkaTemplate<>(producers);
      DeadLetterPublishingRecoverer recoverer =
          new DeadLetterPublishingRecoverer(
              deadLetterTemplate,
              (record, exception) -> new TopicPartition(Topics.deadLetterOf(record.topic()), -1));
      return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    /**
     * Listener containers on platform threads. With {@code spring.threads.virtual.enabled}, Spring
     * Boot runs each consumer loop on a virtual thread. On Java 21 a virtual thread that waits
     * inside a {@code synchronized} block keeps its carrier thread: Kafka closes a consumer inside
     * {@code synchronized} {@code AppInfoParser.unregisterAppInfo} and logs from there, waiting on
     * Logback's lock. With every carrier held that way, the thread holding Logback's lock never
     * runs again, and the service hangs when it stops (seen in Core's tests, which then ran into
     * CI's time limit). Request handling and other work keep their virtual threads.
     */
    @Bean
    @ConditionalOnMissingBean(name = "solaceKafkaListenerThreads")
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
        solaceKafkaListenerThreads() {
      return container ->
          container
              .getContainerProperties()
              .setListenerTaskExecutor(
                  new SimpleAsyncTaskExecutor(container.getListenerId() + "-"));
    }

    @SuppressWarnings("unchecked")
    private static Serializer<Object> keySerializer() {
      Serializer<?> strings = new StringSerializer();
      return (Serializer<Object>) strings;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
  static class Migrations {
    @Bean
    FlywayConfigurationCustomizer eventMigrations() {
      return configuration -> {
        List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
        Location events = new Location(MIGRATIONS);
        if (!locations.contains(events)) {
          locations.add(events);
        }
        configuration.locations(locations.toArray(Location[]::new));
      };
    }
  }
}
