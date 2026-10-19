package care.solace.ai;

import care.solace.ai.gateway.GatewaySettings;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI service (System Design, AI service): every model call goes through here, through the model
 * gateway (D56). At public launch it runs only the safety monitor (D12); every later feature is off
 * until its evaluation passes in EN and FR and it is switched on in AI controls.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GatewaySettings.class)
public class AiApplication {

  public static void main(String[] args) {
    SpringApplication.run(AiApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
