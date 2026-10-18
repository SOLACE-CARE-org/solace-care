package care.solace.core.notifications;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Message delivery for other modules: the copy, push, SMS and email (D38). */
public interface Delivery {

  /** A message from the copy files, word for word, in English or French. */
  String text(String key, Locale locale, Object... arguments);

  /**
   * @param data a booking id at most; never names, health data or free text
   * @return device tokens the push service no longer knows, to forget
   */
  Set<String> push(List<String> tokens, String text, Map<String, String> data);

  /** Sends one SMS; refuses (IllegalArgumentException) text that would not fit one message. */
  void sms(String phone, String text);

  void email(String address, String subject, String body);

  /** Length in GSM-7 characters, or -1 when the text needs another alphabet. */
  int smsLength(String text);
}
