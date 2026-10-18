package care.solace.core.notifications;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** {@link Delivery} over the copy files and the configured senders. */
@Service
class Senders implements Delivery {

  private final Copy copy;
  private final PushSender push;
  private final SmsSender sms;
  private final EmailSender email;

  Senders(Copy copy, PushSender push, SmsSender sms, EmailSender email) {
    this.copy = copy;
    this.push = push;
    this.sms = sms;
    this.email = email;
  }

  @Override
  public String text(String key, Locale locale, Object... arguments) {
    return copy.text(key, locale, arguments);
  }

  @Override
  public Set<String> push(List<String> tokens, String text, Map<String, String> data) {
    return push.send(tokens, text, data);
  }

  @Override
  public void sms(String phone, String text) {
    sms.send(phone, Sms.checked(text));
  }

  @Override
  public void email(String address, String subject, String body) {
    email.send(address, subject, body);
  }

  @Override
  public int smsLength(String text) {
    return Sms.gsm7Length(text);
  }
}
