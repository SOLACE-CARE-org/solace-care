package care.solace.core.notifications;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The push sender chosen by {@code solace.notifications.push-sender} (D38). */
@Configuration
@EnableConfigurationProperties(DeliverySettings.class)
class NotificationsConfiguration {

  @Bean
  PushSender pushSender(DeliverySettings settings, RestClient.Builder restClients) {
    return switch (settings.pushSender()) {
      case "expo" ->
          new ExpoPushSender(restClients, settings.expoUrl(), settings.expoAccessToken());
      case "logging" -> new LoggingPushSender();
      default ->
          throw new IllegalStateException(
              "solace.notifications.push-sender must be expo or logging, not '"
                  + settings.pushSender()
                  + "'");
    };
  }
}
