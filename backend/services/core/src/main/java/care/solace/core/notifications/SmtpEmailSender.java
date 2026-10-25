package care.solace.core.notifications;

import java.util.Map;
import java.util.Properties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Email through any mail server (D61). Settings: {@code host}, {@code port} (587 by default),
 * {@code starttls} (true by default), {@code from} and {@code fromName}. The username and password
 * come from the row's key files. Nothing about the message is logged.
 */
class SmtpEmailSender implements EmailSender {

  private final String code;
  private final JavaMailSenderImpl mail;
  private final String from;

  record Settings(String host, int port, boolean starttls, String from, String fromName) {

    static Settings of(Map<String, Object> raw) {
      if (!(raw.get("host") instanceof String host)
          || host.isBlank()
          || !(raw.get("from") instanceof String from)
          || !from.contains("@")) {
        throw new IllegalArgumentException("SMTP needs host and from");
      }
      int port = raw.get("port") instanceof Number n ? n.intValue() : 587;
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("SMTP port out of range");
      }
      boolean starttls = !(raw.get("starttls") instanceof Boolean b) || b;
      return new Settings(
          host, port, starttls, from, String.valueOf(raw.getOrDefault("fromName", "Solace")));
    }
  }

  SmtpEmailSender(String code, Settings settings, String username, String password) {
    this.code = code;
    this.mail = new JavaMailSenderImpl();
    mail.setHost(settings.host());
    mail.setPort(settings.port());
    mail.setDefaultEncoding("UTF-8");
    Properties props = mail.getJavaMailProperties();
    if (username != null && !username.isBlank()) {
      mail.setUsername(username);
      mail.setPassword(password);
      props.put("mail.smtp.auth", "true");
    }
    props.put("mail.smtp.starttls.enable", String.valueOf(settings.starttls()));
    props.put("mail.smtp.starttls.required", String.valueOf(settings.starttls()));
    props.put("mail.smtp.connectiontimeout", "5000");
    props.put("mail.smtp.timeout", "10000");
    this.from = settings.fromName() + " <" + settings.from() + ">";
  }

  @Override
  public void send(String address, String subject, String body) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(address);
    message.setSubject(subject);
    message.setText(body);
    try {
      mail.send(message);
    } catch (MailException e) {
      throw new MessageSenders.SendFailed(code, e.getClass().getSimpleName());
    }
  }
}
