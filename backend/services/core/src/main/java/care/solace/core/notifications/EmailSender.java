package care.solace.core.notifications;

/** Hands one email to a mail server, picked by {@link MessageSenders} (D61). */
interface EmailSender {

  void send(String address, String subject, String body);
}
