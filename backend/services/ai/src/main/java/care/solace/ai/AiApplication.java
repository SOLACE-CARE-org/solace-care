package care.solace.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI service (System Design, AI service): every model call goes through here. At public launch it
 * runs only the safety monitor (D12); every later feature sits behind a feature flag.
 */
@SpringBootApplication
public class AiApplication {

  public static void main(String[] args) {
    SpringApplication.run(AiApplication.class, args);
  }
}
