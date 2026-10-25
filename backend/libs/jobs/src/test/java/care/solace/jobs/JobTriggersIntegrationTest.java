package care.solace.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Jobs follow their rows across restarts (D61): a new job gets a row with the code's defaults and
 * runs; once its row is PAUSED, the next start does not register it while the others run; resumed
 * with a new schedule, the start after runs it on that schedule.
 */
@Testcontainers
class JobTriggersIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final AtomicInteger TICKS = new AtomicInteger();
  static final AtomicInteger OTHER_TICKS = new AtomicInteger();

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableScheduling
  static class App {
    @Bean
    Ticker ticker() {
      return new Ticker();
    }
  }

  static class Ticker {
    @Job(name = "test.counter.tick", description = "Counts", every = "${test.every:100}")
    void tick() {
      TICKS.incrementAndGet();
    }

    @Job(name = "test.other.tick", description = "Counts too", every = "100")
    void otherTick() {
      OTHER_TICKS.incrementAndGet();
    }
  }

  private static ConfigurableApplicationContext start() {
    TICKS.set(0);
    OTHER_TICKS.set(0);
    return new SpringApplicationBuilder(App.class)
        .web(WebApplicationType.NONE)
        .properties(
            Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword()))
        .run();
  }

  @Test
  void aPausedJobIsNotRegisteredAtTheNextStartAndAResumedOneRunsOnItsNewSchedule() {
    try (var first = start()) {
      var store = first.getBean(JobTriggerStore.class);
      await().atMost(Duration.ofSeconds(10)).until(() -> TICKS.get() > 0 && OTHER_TICKS.get() > 0);
      var row = store.find("test.counter.tick").orElseThrow();
      assertThat(row.status()).isEqualTo(JobTriggerStore.Status.ACTIVE);
      assertThat(row.kind()).isEqualTo(JobTriggerStore.Kind.INTERVAL);
      assertThat(row.schedule()).isEqualTo("PT0.1S");
      assertThat(row.registeredVersion()).isEqualTo(1);
      assertThat(row.pendingRestart()).isFalse();
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> "OK".equals(store.find("test.counter.tick").orElseThrow().lastOutcome()));

      assertThat(
              store.change(
                  "test.counter.tick",
                  1,
                  JobTriggerStore.Status.PAUSED,
                  JobTriggerStore.Kind.INTERVAL,
                  "PT0.1S",
                  null,
                  "Test",
                  "sa",
                  "S",
                  java.time.Instant.now()))
          .isTrue();
      assertThat(store.find("test.counter.tick").orElseThrow().pendingRestart()).isTrue();
    }

    try (var second = start()) {
      var store = second.getBean(JobTriggerStore.class);
      await().atMost(Duration.ofSeconds(10)).until(() -> OTHER_TICKS.get() > 3);
      assertThat(TICKS.get()).isZero();
      var paused = store.find("test.counter.tick").orElseThrow();
      assertThat(paused.registeredVersion()).isNull();
      assertThat(paused.pendingRestart()).isFalse();
      assertThat(
              store.change(
                  "test.counter.tick",
                  2,
                  JobTriggerStore.Status.ACTIVE,
                  JobTriggerStore.Kind.INTERVAL,
                  "PT0.2S",
                  null,
                  "Test",
                  "sa",
                  "S",
                  java.time.Instant.now()))
          .isTrue();
    }

    try (var third = start()) {
      var jdbc = third.getBean(JdbcTemplate.class);
      await().atMost(Duration.ofSeconds(10)).until(() -> TICKS.get() > 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT registered_version FROM job_trigger WHERE name = 'test.counter.tick'",
                  Integer.class))
          .isEqualTo(3);
    }
  }
}
