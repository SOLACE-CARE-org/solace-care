package care.solace.core.notifications;

/** Hands one email to the email provider, not chosen yet (see {@link SmsSender}). */
interface EmailSender {

  void send(String address, String subject, String body);
}
