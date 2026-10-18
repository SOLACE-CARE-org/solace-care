package care.solace.core.notifications;

import java.util.Collection;

/**
 * Browser push to staff (D38, D47): the exact copy from notification-copy.md (Staff), in each
 * subscription's portal language. Staff messages carry no personal details.
 */
public interface StaffPush {

  /**
   * @param key a {@code push.staff.*} copy key
   * @param urgent crisis alerts: the push service delivers at once
   */
  void send(Collection<String> staffIds, String key, boolean urgent, Object... arguments);
}
