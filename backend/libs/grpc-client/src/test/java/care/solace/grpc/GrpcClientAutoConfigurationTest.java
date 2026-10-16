package care.solace.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class GrpcClientAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class));

  @Test
  void everyServiceGetsTheDeadlineAndBreakers() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(DeadlineInterceptor.class);
          assertThat(context).hasSingleBean(ResilientCalls.class);
          assertThat(context).doesNotHaveBean(BearerTokenInterceptor.class);
        });
  }

  @Test
  void breakerSettingsCanBeChanged() {
    runner
        .withPropertyValues("solace.grpc.breaker.failure-rate-threshold=25")
        .run(
            context ->
                assertThat(context.getBean(BreakerSettings.class).failureRateThreshold())
                    .isEqualTo(25f));
  }

  @Test
  void channelsBalancePerCallWithTheTwoSecondDeadline() {
    StandardEnvironment environment = new StandardEnvironment();
    new GrpcClientDefaults().postProcessEnvironment(environment, new SpringApplication());

    assertThat(
            environment.getProperty(
                "spring.grpc.client.default-channel.default-load-balancing-policy"))
        .isEqualTo("round_robin");
    assertThat(environment.getProperty("spring.grpc.client.default-channel.default-deadline"))
        .isEqualTo("2000ms");
  }
}
