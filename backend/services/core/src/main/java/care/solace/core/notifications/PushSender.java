package care.solace.core.notifications;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hands one push to the push service (D38). */
interface PushSender {

  /**
   * Sends the text to every device token.
   *
   * @param data a booking id at most; never names, health data or free text
   * @return tokens the service says are no longer registered, to forget
   */
  Set<String> send(List<String> tokens, String text, Map<String, String> data);
}
