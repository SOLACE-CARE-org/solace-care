package care.solace.core.notifications;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
class CopySignInCodes implements SignInCodes {

  private final Copy copy;
  private final SmsSender sms;
  private final EmailSender email;

  CopySignInCodes(Copy copy, SmsSender sms, EmailSender email) {
    this.copy = copy;
    this.sms = sms;
    this.email = email;
  }

  @Override
  public void send(Channel channel, String to, String code, int minutes, Locale locale) {
    String lifetime = Integer.toString(minutes);
    switch (channel) {
      case SMS -> sms.send(to, Sms.checked(copy.text("sms.sign-in-code", locale, code, lifetime)));
      case EMAIL ->
          email.send(
              to,
              copy.text("email.sign-in-code.subject", locale, code),
              copy.text("email.sign-in-code.body", locale, code, lifetime));
    }
  }
}
