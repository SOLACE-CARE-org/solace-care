package care.solace.core.notifications;

/**
 * Hands one SMS to the SMS provider. The provider is not chosen yet (decisions.md, Still open), so
 * the only adapter is {@link LoggingMessageSender}, for local development.
 */
interface SmsSender {

  void send(String phone, String text);
}
