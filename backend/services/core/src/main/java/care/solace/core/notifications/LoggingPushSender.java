package care.solace.core.notifications;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development and tests only: writes pushes to the log (solace.notifications.push-sender).
 */
class LoggingPushSender implements PushSender {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingPushSender.class);

  @Override
  public Set<String> send(List<String> tokens, String text, Map<String, String> data) {
    LOG.info("[DEV ONLY] push to {} device(s): {}", tokens.size(), text);
    return Set.of();
  }
}
