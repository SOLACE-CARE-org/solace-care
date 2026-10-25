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
 * runs; once its row is PAUSED, the next start does not register it; a locked job runs whatever its
 * row says.
 */
@Testcontainers
class JobTriggersIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final AtomicInteger TICKS = new AtomicInteger();
  static final AtomicInteger LOCKED_TICKS = new AtomicInteger();

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

    @Job(name = "test.locked.tick", description = "Counts, locked", every = "100", locked = true)
    void lockedTick() {
      LOCKED_TICKS.incrementAndGet();
    }
  }

  private static ConfigurableApplicationContext start() {
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
  void aPausedJobIsNotRegisteredAtTheNextStartButALockedOneIs() {
    try (var first = start()) {
      var jdbc = first.getBean(JdbcTemplate.class);
      await().atMost(Duration.ofSeconds(10)).until(() -> TICKS.get() > 0 && LOCKED_TICKS.get() > 0);
      var row = first.getBean(JobTriggerStore.class).find("test.counter.tick").orElseThrow();
      assertThat(row.status()).isEqualTo(JobTriggerStore.Status.ACTIVE);
      assertThat(row.kind()).isEqualTo(JobTriggerStore.Kind.INTERVAL);
      assertThat(row.schedule()).isEqualTo("PT0.1S");
      assertThat(row.registeredVersion()).isEqualTo(1);
      assertThat(row.pendingRestart()).isFalse();
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  "OK"
                      .equals(
                          first
                              .getBean(JobTriggerStore.class)
                              .find("test.counter.tick")
                              .orElseThrow()
                              .lastOutcome()));

      jdbc.update("UPDATE job_trigger SET status = 'PAUSED', version = version + 1");
      assertThat(first.getBean(JobTriggerStore.class).find("test.counter.tick").orElseThrow())
          .matches(JobTriggerStore.Trigger::pendingRestart, "waits for a restart");
    }

    TICKS.set(0);
    LOCKED_TICKS.set(0);
    try (var second = start()) {
      await().atMost(Duration.ofSeconds(10)).until(() -> LOCKED_TICKS.get() > 3);
      assertThat(TICKS.get()).isZero();
      var store = second.getBean(JobTriggerStore.class);
      var paused = store.find("test.counter.tick").orElseThrow();
      assertThat(paused.registeredVersion()).isNull();
      assertThat(paused.pendingRestart()).isFalse();
      assertThat(store.find("test.locked.tick").orElseThrow().locked()).isTrue();
    }
  }
}
