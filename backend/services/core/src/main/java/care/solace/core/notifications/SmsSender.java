package care.solace.core.notifications;

/**
 * Hands one SMS to an SMS gateway. {@link MessageSenders} picks the gateway from the sender rows
 * (D61); {@link HttpSmsSender} speaks to any gateway with an HTTP API.
 */
interface SmsSender {

  void send(String phone, String text);
}
