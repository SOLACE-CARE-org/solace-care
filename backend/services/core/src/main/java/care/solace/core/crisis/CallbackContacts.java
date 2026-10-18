package care.solace.core.crisis;

import java.util.Optional;
import java.util.UUID;

/**
 * The number a guest left for a call back, for sources that collect one (the health check). The
 * crisis responder sees it masked until they open the alert, like any other contact.
 */
public interface CallbackContacts {

  Optional<String> phone(String source, UUID sourceId);
}
